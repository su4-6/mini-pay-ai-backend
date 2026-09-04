package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.service.ConsumerOnboardingService.CompletionResult;
import com.minipay.identity.application.service.OnboardingRejectedException;
import com.minipay.identity.domain.model.ConsumerProfile;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.AvatarUpload;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConsumerOnboardingRepository {
    private final JdbcTemplate jdbcTemplate;

    public ConsumerOnboardingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CompletionResult> findReplay(
            UUID userId, String idempotencyKey, byte[] requestHash) {
        ExistingRequest existing = findRequest(idempotencyKey).orElse(null);
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.userId().equals(userId)
                || !Arrays.equals(existing.requestHash(), requestHash)) {
            throw new OnboardingRejectedException("IDEMPOTENCY_KEY_REUSED");
        }
        return Optional.of(new CompletionResult(loadProfile(userId), false));
    }

    @Transactional
    public CompletionResult complete(
            UUID userId,
            String idempotencyKey,
            byte[] requestHash,
            String nickname,
            AvatarUpload avatar) {
        OnboardingRow row = lockProfile(userId)
                .orElseThrow(() -> new OnboardingRejectedException("CONSUMER_NOT_FOUND"));
        ExistingRequest existing = findRequest(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.userId().equals(userId)
                    || !Arrays.equals(existing.requestHash(), requestHash)) {
                throw new OnboardingRejectedException("IDEMPOTENCY_KEY_REUSED");
            }
            return new CompletionResult(loadProfile(userId), false);
        }
        if ("COMPLETED".equals(row.onboardingStatus())) {
            throw new OnboardingRejectedException("ONBOARDING_ALREADY_COMPLETED");
        }
        AvatarUpload lockedAvatar = avatar == null ? null : lockAvatar(avatar.uploadId(), userId);

        int updated = jdbcTemplate.update("""
                UPDATE user_profile
                SET nickname = ?, avatar_object_key = ?, onboarding_status = 'COMPLETED',
                    onboarding_completed_at = UTC_TIMESTAMP(6), version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND onboarding_status = 'PENDING'
                """,
                nickname,
                lockedAvatar == null ? null : lockedAvatar.objectKey(),
                AdminAccountRepository.uuidToBytes(userId));
        if (updated != 1) {
            throw new IllegalStateException("Consumer onboarding update did not converge");
        }
        if (lockedAvatar != null) {
            int consumed = jdbcTemplate.update("""
                    UPDATE avatar_upload
                    SET status = 'CONSUMED', consumed_at = UTC_TIMESTAMP(6),
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE upload_id = ? AND status = 'PENDING'
                    """, AdminAccountRepository.uuidToBytes(lockedAvatar.uploadId()));
            if (consumed != 1) {
                throw new OnboardingRejectedException("AVATAR_UPLOAD_EXPIRED");
            }
        }
        jdbcTemplate.update("""
                INSERT INTO consumer_onboarding_request (
                  idempotency_key, user_id, request_hash, completed_at
                ) VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                """,
                idempotencyKey,
                AdminAccountRepository.uuidToBytes(userId),
                requestHash);
        return new CompletionResult(loadProfile(userId), true);
    }

    private Optional<OnboardingRow> lockProfile(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT onboarding_status
                        FROM user_profile
                        WHERE user_id = ? AND status = 'ACTIVE'
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new OnboardingRow(resultSet.getString("onboarding_status")))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId));
    }

    private Optional<ExistingRequest> findRequest(String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT user_id, request_hash
                        FROM consumer_onboarding_request
                        WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ExistingRequest(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getBytes("request_hash")))
                        : Optional.empty(),
                idempotencyKey);
    }

    private AvatarUpload lockAvatar(UUID uploadId, UUID userId) {
        AvatarUpload upload = jdbcTemplate.query("""
                        SELECT upload_id, user_id, object_key, expected_content_type,
                               expected_size, expected_sha256, status, expires_at
                        FROM avatar_upload
                        WHERE upload_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new AvatarUpload(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("upload_id")),
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("object_key"),
                        resultSet.getString("expected_content_type"),
                        resultSet.getLong("expected_size"),
                        resultSet.getString("expected_sha256"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("expires_at").toInstant())
                        : null,
                AdminAccountRepository.uuidToBytes(uploadId));
        if (upload == null) {
            throw new OnboardingRejectedException("AVATAR_UPLOAD_NOT_FOUND");
        }
        if (!upload.userId().equals(userId)) {
            throw new OnboardingRejectedException("AVATAR_UPLOAD_FORBIDDEN");
        }
        if (!"PENDING".equals(upload.status()) || upload.expiresAt().isBefore(Instant.now())) {
            throw new OnboardingRejectedException("AVATAR_UPLOAD_EXPIRED");
        }
        return upload;
    }

    private ConsumerProfile loadProfile(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT u.user_id, u.nickname, u.avatar_object_key, u.onboarding_status,
                               EXISTS(
                                 SELECT 1 FROM user_credential c
                                 WHERE c.user_id = u.user_id
                                   AND c.credential_type = 'PAYMENT_PASSWORD'
                                   AND c.status = 'ACTIVE'
                               ) AS pay_password_set
                        FROM user_profile u
                        WHERE u.user_id = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new OnboardingRejectedException("CONSUMER_NOT_FOUND");
                    }
                    return new ConsumerProfile(
                            AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                            resultSet.getString("nickname"),
                            resultSet.getString("avatar_object_key"),
                            resultSet.getBoolean("pay_password_set"),
                            "COMPLETED".equals(resultSet.getString("onboarding_status")));
                },
                AdminAccountRepository.uuidToBytes(userId));
    }

    private record OnboardingRow(String onboardingStatus) {
    }

    private record ExistingRequest(UUID userId, byte[] requestHash) {
    }
}
