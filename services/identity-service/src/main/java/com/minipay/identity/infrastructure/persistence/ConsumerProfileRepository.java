package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.service.ProfileRejectedException;
import com.minipay.identity.application.service.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConsumerProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public ConsumerProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProfileRow> find(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT user_id, nickname, minipay_no, avatar_object_key, version
                        FROM user_profile
                        WHERE user_id = ? AND status = 'ACTIVE'
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ProfileRow(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("nickname"),
                        resultSet.getString("minipay_no"),
                        resultSet.getString("avatar_object_key"),
                        resultSet.getLong("version")))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId));
    }

    public void insertUpload(AvatarUpload upload) {
        jdbcTemplate.update("""
                INSERT INTO avatar_upload (
                  upload_id, user_id, object_key, expected_content_type, expected_size,
                  expected_sha256, status, expires_at, consumed_at, cleanup_attempts,
                  next_cleanup_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL, 0, NULL,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(upload.uploadId()),
                AdminAccountRepository.uuidToBytes(upload.userId()),
                upload.objectKey(), upload.contentType(), upload.size(), upload.sha256(),
                java.sql.Timestamp.from(upload.expiresAt()));
    }

    public Optional<AvatarUpload> findUpload(UUID uploadId) {
        return jdbcTemplate.query("""
                        SELECT upload_id, user_id, object_key, expected_content_type,
                               expected_size, expected_sha256, status, expires_at
                        FROM avatar_upload
                        WHERE upload_id = ?
                        """,
                resultSet -> resultSet.next() ? Optional.of(mapUpload(resultSet)) : Optional.empty(),
                AdminAccountRepository.uuidToBytes(uploadId));
    }

    @Transactional
    public ProfileRow update(
            UUID userId, String nickname, AvatarUpload avatar, long expectedVersion) {
        ProfileRow current = lockProfile(userId);
        if (current.version() != expectedVersion) {
            throw new ProfileRejectedException("PROFILE_VERSION_CONFLICT");
        }
        if (avatar != null) {
            AvatarUpload locked = lockUpload(avatar.uploadId());
            if (!locked.userId().equals(userId)) {
                throw new ProfileRejectedException("AVATAR_UPLOAD_FORBIDDEN");
            }
            if (!"PENDING".equals(locked.status()) || locked.expiresAt().isBefore(Instant.now())) {
                throw new ProfileRejectedException("AVATAR_UPLOAD_EXPIRED");
            }
        }
        String nextObjectKey = avatar == null ? current.avatarObjectKey() : avatar.objectKey();
        int changed = jdbcTemplate.update("""
                UPDATE user_profile
                SET nickname = ?, avatar_object_key = ?, version = version + 1,
                    disclosure_version = disclosure_version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND status = 'ACTIVE' AND version = ?
                """,
                nickname, nextObjectKey, AdminAccountRepository.uuidToBytes(userId), expectedVersion);
        if (changed != 1) {
            throw new ProfileRejectedException("PROFILE_VERSION_CONFLICT");
        }
        if (avatar != null) {
            jdbcTemplate.update("""
                    UPDATE avatar_upload
                    SET status = 'CONSUMED', consumed_at = UTC_TIMESTAMP(6),
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE upload_id = ? AND status = 'PENDING'
                    """, AdminAccountRepository.uuidToBytes(avatar.uploadId()));
            if (current.avatarObjectKey() != null
                    && !current.avatarObjectKey().equals(avatar.objectKey())) {
                jdbcTemplate.update("""
                        UPDATE avatar_upload
                        SET status = 'DELETE_PENDING', next_cleanup_at = UTC_TIMESTAMP(6),
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE object_key = ? AND status = 'CONSUMED'
                        """, current.avatarObjectKey());
            }
        }
        appendDisclosureChanged(userId);
        return find(userId).orElseThrow(() -> new ProfileRejectedException("CONSUMER_NOT_FOUND"));
    }

    private void appendDisclosureChanged(UUID userId) {
        UUID eventId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
                ) VALUES (?, 'identity.user-disclosure-profile.changed', 'consumer', ?,
                          UTC_TIMESTAMP(6), NULL, 1, ?, 'PENDING', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(eventId),
                AdminAccountRepository.uuidToBytes(userId),
                "{\"userId\":\"" + userId + "\"}");
    }

    public void rejectUpload(UUID uploadId) {
        jdbcTemplate.update("""
                UPDATE avatar_upload
                SET status = 'REJECTED', next_cleanup_at = UTC_TIMESTAMP(6),
                    updated_at = UTC_TIMESTAMP(6)
                WHERE upload_id = ? AND status = 'PENDING'
                """, AdminAccountRepository.uuidToBytes(uploadId));
    }

    public List<AvatarUpload> cleanupCandidates(int limit) {
        jdbcTemplate.update("""
                UPDATE avatar_upload
                SET status = 'EXPIRED', next_cleanup_at = UTC_TIMESTAMP(6),
                    updated_at = UTC_TIMESTAMP(6)
                WHERE status = 'PENDING' AND expires_at < UTC_TIMESTAMP(6)
                """);
        return jdbcTemplate.query("""
                        SELECT upload_id, user_id, object_key, expected_content_type,
                               expected_size, expected_sha256, status, expires_at
                        FROM avatar_upload
                        WHERE status IN ('REJECTED', 'EXPIRED', 'DELETE_PENDING')
                          AND next_cleanup_at <= UTC_TIMESTAMP(6)
                        ORDER BY next_cleanup_at, upload_id
                        LIMIT ?
                        """, (resultSet, rowNum) -> mapUpload(resultSet), limit);
    }

    public void markDeleted(UUID uploadId) {
        jdbcTemplate.update("""
                UPDATE avatar_upload
                SET status = 'DELETED', next_cleanup_at = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE upload_id = ?
                """, AdminAccountRepository.uuidToBytes(uploadId));
    }

    public void rescheduleCleanup(UUID uploadId) {
        jdbcTemplate.update("""
                UPDATE avatar_upload
                SET cleanup_attempts = cleanup_attempts + 1,
                    next_cleanup_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE),
                    updated_at = UTC_TIMESTAMP(6)
                WHERE upload_id = ?
                """, AdminAccountRepository.uuidToBytes(uploadId));
    }

    private ProfileRow lockProfile(UUID userId) {
        ProfileRow row = jdbcTemplate.query("""
                        SELECT user_id, nickname, minipay_no, avatar_object_key, version
                        FROM user_profile
                        WHERE user_id = ? AND status = 'ACTIVE'
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new ProfileRow(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("nickname"), resultSet.getString("minipay_no"),
                        resultSet.getString("avatar_object_key"), resultSet.getLong("version"))
                        : null,
                AdminAccountRepository.uuidToBytes(userId));
        if (row == null) {
            throw new ProfileRejectedException("CONSUMER_NOT_FOUND");
        }
        return row;
    }

    private AvatarUpload lockUpload(UUID uploadId) {
        AvatarUpload upload = jdbcTemplate.query("""
                        SELECT upload_id, user_id, object_key, expected_content_type,
                               expected_size, expected_sha256, status, expires_at
                        FROM avatar_upload WHERE upload_id = ? FOR UPDATE
                        """,
                resultSet -> resultSet.next() ? mapUpload(resultSet) : null,
                AdminAccountRepository.uuidToBytes(uploadId));
        if (upload == null) {
            throw new ProfileRejectedException("AVATAR_UPLOAD_NOT_FOUND");
        }
        return upload;
    }

    private AvatarUpload mapUpload(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new AvatarUpload(
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("upload_id")),
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                resultSet.getString("object_key"), resultSet.getString("expected_content_type"),
                resultSet.getLong("expected_size"), resultSet.getString("expected_sha256"),
                resultSet.getString("status"), resultSet.getTimestamp("expires_at").toInstant());
    }

    public record ProfileRow(
            UUID userId, String nickname, String minipayNo, String avatarObjectKey, long version) {
    }

    public record AvatarUpload(
            UUID uploadId, UUID userId, String objectKey, String contentType, long size,
            String sha256, String status, Instant expiresAt) {
    }
}
