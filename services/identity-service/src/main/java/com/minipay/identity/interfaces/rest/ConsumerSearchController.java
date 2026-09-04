package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Validated
public class ConsumerSearchController {
    private static final int SEARCH_LIMIT = 30;
    private static final java.util.regex.Pattern MAINLAND_MOBILE =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    private final JdbcTemplate jdbc;
    private final PhoneNumberService phoneNumbers;
    private final ObjectStoragePort storage;
    private final Duration avatarReadTtl;

    public ConsumerSearchController(JdbcTemplate jdbc, PhoneNumberService phoneNumbers,
            ObjectStoragePort storage,
            @Value("${minipay.identity.profile.read-url-ttl}") Duration avatarReadTtl) {
        this.jdbc = jdbc;
        this.phoneNumbers = phoneNumbers;
        this.storage = storage;
        this.avatarReadTtl = avatarReadTtl;
    }

    @GetMapping("/search")
    public List<SearchHit> search(
            @RequestParam("q") @NotBlank @Size(max = 128) String q,
            JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        String keyword = q.strip();
        LinkedHashSet<SearchHit> results = new LinkedHashSet<>();
        byte[] currentUserBytes = AdminAccountRepository.uuidToBytes(userId);

        boolean isPhoneSearch = MAINLAND_MOBILE.matcher(keyword).matches();

        // 1. Phone search — search ALL users (including strangers)
        if (isPhoneSearch) {
            try {
                String normalized = phoneNumbers.normalize(keyword);
                byte[] phoneHash = phoneNumbers.hash(normalized);
                results.addAll(jdbc.query("""
                        SELECT up.user_id, up.nickname, up.minipay_no, up.phone_masked, up.avatar_object_key,
                               CASE
                                   WHEN fr_rel.user_id IS NOT NULL THEN 'ACCEPTED'
                                   WHEN fr_req.from_user_id IS NOT NULL THEN 'PENDING'
                                   ELSE NULL
                               END AS friend_status
                        FROM user_profile up
                        LEFT JOIN friend_relation fr_rel ON fr_rel.friend_id = up.user_id AND fr_rel.user_id = ? AND fr_rel.deleted_at IS NULL
                        LEFT JOIN friend_request fr_req ON fr_req.to_user_id = up.user_id AND fr_req.from_user_id = ? AND fr_req.status = 'PENDING'
                        WHERE up.status = 'ACTIVE'
                          AND up.user_id != ?
                          AND up.phone_hash = ?
                        LIMIT ?
                        """,
                        (rs, rowNum) -> searchHit(
                                AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")).toString(),
                                rs.getString("nickname"),
                                rs.getString("minipay_no"),
                                rs.getString("phone_masked"),
                                rs.getString("friend_status"),
                                rs.getString("avatar_object_key")),
                        currentUserBytes, currentUserBytes, currentUserBytes, phoneHash, SEARCH_LIMIT));
            } catch (IllegalArgumentException ignored) {
                // Invalid phone format
            }
        }

        // 2. Nickname / minipay_no search — only among FRIENDS
        int remaining = SEARCH_LIMIT - results.size();
        if (remaining > 0 && !isPhoneSearch) {
            // Exact match on minipay_no (friends only)
            results.addAll(jdbc.query("""
                    SELECT up.user_id, up.nickname, up.minipay_no, up.phone_masked, up.avatar_object_key
                    FROM user_profile up
                    JOIN friend_relation fr ON fr.friend_id = up.user_id AND fr.user_id = ? AND fr.deleted_at IS NULL
                    WHERE up.status = 'ACTIVE'
                      AND up.minipay_no = ?
                    LIMIT ?
                    """,
                    (rs, rowNum) -> searchHit(
                            AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")).toString(),
                            rs.getString("nickname"),
                            rs.getString("minipay_no"),
                            rs.getString("phone_masked"),
                            "ACCEPTED", rs.getString("avatar_object_key")),
                    currentUserBytes, keyword.toUpperCase(), remaining));

            // LIKE match on nickname (friends only)
            remaining = SEARCH_LIMIT - results.size();
            if (remaining > 0) {
                results.addAll(jdbc.query("""
                        SELECT up.user_id, up.nickname, up.minipay_no, up.phone_masked, up.avatar_object_key
                        FROM user_profile up
                    JOIN friend_relation fr ON fr.friend_id = up.user_id AND fr.user_id = ? AND fr.deleted_at IS NULL
                        WHERE up.status = 'ACTIVE'
                          AND up.nickname LIKE CONCAT('%', ?, '%')
                        LIMIT ?
                        """,
                        (rs, rowNum) -> searchHit(
                                AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")).toString(),
                                rs.getString("nickname"),
                                rs.getString("minipay_no"),
                                rs.getString("phone_masked"),
                                "ACCEPTED", rs.getString("avatar_object_key")),
                        currentUserBytes, keyword, remaining));
            }
        }

        return new ArrayList<>(results);
    }

    /** Resolves a MiniPay card QR without widening ordinary nickname/account search. */
    @GetMapping("/qr/{minipayNo}")
    public PublicCardResponse resolveQr(
            @PathVariable @NotBlank @Size(max = 32) String minipayNo,
            JwtAuthenticationToken authentication) {
        UUID currentUserId = extractUserId(authentication);
        byte[] currentUserBytes = AdminAccountRepository.uuidToBytes(currentUserId);
        return jdbc.query("""
                SELECT up.user_id, up.nickname, up.minipay_no, up.phone_masked, up.avatar_object_key,
                       CASE
                           WHEN up.user_id = ? THEN 'SELF'
                           WHEN fr_rel.user_id IS NOT NULL THEN 'ACCEPTED'
                           WHEN fr_req.from_user_id IS NOT NULL THEN 'PENDING'
                           ELSE 'NONE'
                       END AS friend_status
                FROM user_profile up
                LEFT JOIN friend_relation fr_rel
                  ON fr_rel.friend_id = up.user_id AND fr_rel.user_id = ? AND fr_rel.deleted_at IS NULL
                LEFT JOIN friend_request fr_req
                  ON fr_req.to_user_id = up.user_id AND fr_req.from_user_id = ? AND fr_req.status = 'PENDING'
                WHERE up.status = 'ACTIVE' AND up.minipay_no = ?
                LIMIT 1
                """, resultSet -> resultSet.next()
                        ? publicCard(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")).toString(),
                        resultSet.getString("nickname"), resultSet.getString("minipay_no"),
                        resultSet.getString("phone_masked"), resultSet.getString("friend_status"),
                        resultSet.getString("avatar_object_key"))
                        : null,
                currentUserBytes, currentUserBytes, currentUserBytes, minipayNo.strip().toUpperCase());
    }

    private static UUID extractUserId(JwtAuthenticationToken authentication) {
        String claim = authentication.getToken().getClaimAsString("user_id");
        if (claim == null) {
            throw new IllegalArgumentException("JWT missing user_id claim");
        }
        return UUID.fromString(claim);
    }

    private SearchHit searchHit(String userId, String nickname, String minipayNo, String phoneMasked,
            String friendStatus, String avatarObjectKey) {
        SignedAvatar avatar = signedAvatar(avatarObjectKey);
        return new SearchHit(userId, nickname, minipayNo, phoneMasked, friendStatus,
                avatar.url(), avatar.expiresAt());
    }

    private PublicCardResponse publicCard(String userId, String nickname, String minipayNo,
            String phoneMasked, String friendStatus, String avatarObjectKey) {
        SignedAvatar avatar = signedAvatar(avatarObjectKey);
        return new PublicCardResponse(userId, nickname, minipayNo, phoneMasked, friendStatus,
                avatar.url(), avatar.expiresAt());
    }

    private SignedAvatar signedAvatar(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return new SignedAvatar(null, null);
        try {
            ObjectStoragePort.SignedRead signed = storage.signRead(objectKey, avatarReadTtl);
            return new SignedAvatar(signed.url().toString(), signed.expiresAt());
        } catch (RuntimeException ignored) {
            return new SignedAvatar(null, null);
        }
    }

    private record SignedAvatar(String url, Instant expiresAt) {}

    public record SearchHit(String userId, String nickname, String minipayNo, String phoneMasked,
                            String friendStatus, String avatarUrl, Instant avatarUrlExpiresAt) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SearchHit other)) return false;
            return userId.equals(other.userId);
        }

        @Override
        public int hashCode() {
            return userId.hashCode();
        }
    }

    public record PublicCardResponse(
            String userId, String nickname, String minipayNo, String phoneMasked, String friendStatus,
            String avatarUrl, Instant avatarUrlExpiresAt) {}
}
