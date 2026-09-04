package com.minipay.identity.interfaces.rest;

import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/friend-requests")
@Validated
public class FriendRequestController {
    private final JdbcTemplate jdbc;

    public FriendRequestController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public FriendRequestResponse sendRequest(
            @Valid @RequestBody SendFriendRequestBody body,
            JwtAuthenticationToken authentication) {
        UUID fromUserId = extractUserId(authentication);
        UUID toUserId = UUID.fromString(body.toUserId());

        if (fromUserId.equals(toUserId)) {
            throw new FriendRequestRejectedException("CANNOT_REQUEST_SELF");
        }

        // Check if already friends
        Integer friendCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM friend_relation WHERE user_id = ? AND friend_id = ? AND deleted_at IS NULL",
                Integer.class,
                AdminAccountRepository.uuidToBytes(fromUserId),
                AdminAccountRepository.uuidToBytes(toUserId));
        if (friendCount != null && friendCount > 0) {
            throw new FriendRequestRejectedException("ALREADY_FRIENDS");
        }

        // Check if pending request already exists
        List<UUID> existing = jdbc.query(
                "SELECT id FROM friend_request WHERE from_user_id = ? AND to_user_id = ? AND status = 'PENDING'",
                (rs, rowNum) -> AdminAccountRepository.bytesToUuid(rs.getBytes("id")),
                AdminAccountRepository.uuidToBytes(fromUserId),
                AdminAccountRepository.uuidToBytes(toUserId));
        if (!existing.isEmpty()) {
            return new FriendRequestResponse(existing.get(0).toString(), "PENDING");
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO friend_request (id, from_user_id, to_user_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """,
                    AdminAccountRepository.uuidToBytes(id),
                    AdminAccountRepository.uuidToBytes(fromUserId),
                    AdminAccountRepository.uuidToBytes(toUserId));
        } catch (DuplicateKeyException e) {
            // Race: another concurrent request already inserted a PENDING row
            List<UUID> duplicate = jdbc.query(
                    "SELECT id FROM friend_request WHERE from_user_id = ? AND to_user_id = ? AND status = 'PENDING'",
                    (rs, rowNum) -> AdminAccountRepository.bytesToUuid(rs.getBytes("id")),
                    AdminAccountRepository.uuidToBytes(fromUserId),
                    AdminAccountRepository.uuidToBytes(toUserId));
            if (!duplicate.isEmpty()) {
                return new FriendRequestResponse(duplicate.get(0).toString(), "PENDING");
            }
            throw e;
        }

        return new FriendRequestResponse(id.toString(), "PENDING");
    }

    @GetMapping("/received")
    public List<ReceivedRequest> getReceivedRequests(JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        byte[] userIdBytes = AdminAccountRepository.uuidToBytes(userId);

        return jdbc.query("""
                SELECT fr.id, fr.from_user_id, fr.status, fr.created_at,
                       up.nickname, up.minipay_no, up.phone_masked
                FROM friend_request fr
                JOIN user_profile up ON up.user_id = fr.from_user_id
                WHERE fr.to_user_id = ? AND fr.status = 'PENDING'
                ORDER BY fr.created_at DESC
                """,
                (rs, rowNum) -> new ReceivedRequest(
                        AdminAccountRepository.bytesToUuid(rs.getBytes("id")).toString(),
                        AdminAccountRepository.bytesToUuid(rs.getBytes("from_user_id")).toString(),
                        rs.getString("nickname"),
                        rs.getString("minipay_no"),
                        rs.getString("phone_masked"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant().toEpochMilli()),
                userIdBytes);
    }

    @PutMapping("/{id}/accept")
    @Transactional
    public ResponseEntity<Void> accept(
            @PathVariable String id,
            JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        UUID requestId = UUID.fromString(id);
        byte[] requestIdBytes = AdminAccountRepository.uuidToBytes(requestId);

        // Find the request, verify it's addressed to current user
        List<FriendRequestRow> rows = jdbc.query("""
                SELECT id, from_user_id, to_user_id, status
                FROM friend_request
                WHERE id = ? AND status = 'PENDING'
                FOR UPDATE
                """,
                (rs, rowNum) -> new FriendRequestRow(
                        AdminAccountRepository.bytesToUuid(rs.getBytes("id")),
                        AdminAccountRepository.bytesToUuid(rs.getBytes("from_user_id")),
                        AdminAccountRepository.bytesToUuid(rs.getBytes("to_user_id")),
                        rs.getString("status")),
                requestIdBytes);

        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FriendRequestRow row = rows.get(0);
        if (!row.toUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Remove old accepted/rejected requests for same pair to avoid
        // unique-key violation on (from_user_id, to_user_id, status).
        byte[] fromBytes = AdminAccountRepository.uuidToBytes(row.fromUserId());
        byte[] toBytes = AdminAccountRepository.uuidToBytes(row.toUserId());
        jdbc.update("""
                DELETE FROM friend_request
                WHERE from_user_id = ? AND to_user_id = ? AND status IN ('ACCEPTED', 'REJECTED')
                """, fromBytes, toBytes);

        // Mark request accepted
        jdbc.update("""
                UPDATE friend_request SET status = 'ACCEPTED', updated_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """, requestIdBytes);

        // Restore soft-deleted friend_relation rows (re-add after unfriend).
        // Both direction rows must be active for a complete friendship.
        jdbc.update("""
                UPDATE friend_relation SET deleted_at = NULL
                WHERE (user_id = ? AND friend_id = ? AND deleted_at IS NOT NULL)
                   OR (user_id = ? AND friend_id = ? AND deleted_at IS NOT NULL)
                """, toBytes, fromBytes, fromBytes, toBytes);

        // Insert on first acceptance (rows may not exist yet).
        jdbc.update("""
                INSERT IGNORE INTO friend_relation (id, user_id, friend_id, created_at, deleted_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), NULL)
                """,
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()), toBytes, fromBytes);

        jdbc.update("""
                INSERT IGNORE INTO friend_relation (id, user_id, friend_id, created_at, deleted_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), NULL)
                """,
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()), fromBytes, toBytes);

        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<Void> reject(
            @PathVariable String id,
            JwtAuthenticationToken authentication) {
        UUID userId = extractUserId(authentication);
        UUID requestId = UUID.fromString(id);
        byte[] requestIdBytes = AdminAccountRepository.uuidToBytes(requestId);

        int updated = jdbc.update("""
                UPDATE friend_request SET status = 'REJECTED', updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND to_user_id = ? AND status = 'PENDING'
                """, requestIdBytes, AdminAccountRepository.uuidToBytes(userId));

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().build();
    }

    private static UUID extractUserId(JwtAuthenticationToken authentication) {
        String claim = authentication.getToken().getClaimAsString("user_id");
        if (claim == null) {
            throw new IllegalArgumentException("JWT missing user_id claim");
        }
        return UUID.fromString(claim);
    }

    // --- DTOs ---

    public record SendFriendRequestBody(@NotBlank String toUserId) {}

    public record FriendRequestResponse(String id, String status) {}

    public record ReceivedRequest(
            String id, String fromUserId, String nickname, String minipayNo,
            String phoneMasked, String status, long createdAt) {}

    private record FriendRequestRow(UUID id, UUID fromUserId, UUID toUserId, String status) {}

    public static class FriendRequestRejectedException extends RuntimeException {
        private final String code;

        public FriendRequestRejectedException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
