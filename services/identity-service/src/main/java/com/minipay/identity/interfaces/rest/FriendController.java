package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final Duration avatarReadTtl;

    public FriendController(JdbcTemplate jdbc, ObjectStoragePort storage,
            @Value("${minipay.identity.profile.read-url-ttl}") Duration avatarReadTtl) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.avatarReadTtl = avatarReadTtl;
    }

    @GetMapping
    public List<FriendResponse> listFriends(JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        byte[] userIdBytes = AdminAccountRepository.uuidToBytes(userId);

        List<FriendRow> rows = jdbc.query("""
                SELECT up.user_id, up.nickname, up.minipay_no, up.phone_masked, up.avatar_object_key
                FROM friend_relation fr
                JOIN user_profile up ON up.user_id = fr.friend_id
                WHERE fr.user_id = ?
                  AND fr.deleted_at IS NULL
                  AND up.status = 'ACTIVE'
                ORDER BY up.nickname
                """,
                (rs, rowNum) -> new FriendRow(
                        AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")),
                        rs.getString("nickname"),
                        rs.getString("minipay_no"),
                        rs.getString("phone_masked"),
                        rs.getString("avatar_object_key")),
                userIdBytes);

        return rows.stream()
                .map(row -> response(userId, row))
                .toList();
    }

    private FriendResponse response(UUID ownerId, FriendRow row) {
        String avatarUrl = null;
        Instant avatarUrlExpiresAt = null;
        if (row.avatarObjectKey != null && !row.avatarObjectKey.isBlank()) {
            try {
                ObjectStoragePort.SignedRead signed = storage.signRead(row.avatarObjectKey, avatarReadTtl);
                avatarUrl = signed.url().toString();
                avatarUrlExpiresAt = signed.expiresAt();
            } catch (RuntimeException ignored) {
                // Friend discovery must remain available when object storage is unavailable.
            }
        }
        return new FriendResponse(row.userId.toString(), row.nickname, row.minipayNo,
                row.phoneMasked, conversationId(ownerId, row.userId), avatarUrl, avatarUrlExpiresAt);
    }

    @DeleteMapping("/{friendUserId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFriend(@PathVariable UUID friendUserId, JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        byte[] userBytes = AdminAccountRepository.uuidToBytes(userId);
        byte[] friendBytes = AdminAccountRepository.uuidToBytes(friendUserId);
        jdbc.update("""
                UPDATE friend_relation
                SET deleted_at = UTC_TIMESTAMP(6)
                WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)
                  AND deleted_at IS NULL
                """, userBytes, friendBytes, friendBytes, userBytes);
    }

    private static UUID extractUserId(JwtAuthenticationToken authentication) {
        String claim = authentication.getToken().getClaimAsString("user_id");
        if (claim == null) {
            throw new IllegalArgumentException("JWT missing user_id claim");
        }
        return UUID.fromString(claim);
    }

    private record FriendRow(UUID userId, String nickname, String minipayNo, String phoneMasked,
                             String avatarObjectKey) {}

    public record FriendResponse(
            String userId, String nickname, String minipayNo, String phoneMasked, String conversationId,
            String avatarUrl, Instant avatarUrlExpiresAt) {}

    private static String conversationId(UUID userA, UUID userB) {
        // Deterministic conversation ID — must match agent-service ChatController.conversationId()
        UUID first = userA.compareTo(userB) < 0 ? userA : userB;
        UUID second = userA.compareTo(userB) < 0 ? userB : userA;
        String input = first + ":" + second;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return "conv_" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
