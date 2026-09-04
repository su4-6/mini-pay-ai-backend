package com.minipay.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAuthorizationRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CredentialRow> lockPaymentCredential(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT password_hash, failed_attempts, locked_until, status
                        FROM user_credential
                        WHERE user_id = ? AND credential_type = 'PAYMENT_PASSWORD'
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new CredentialRow(
                        resultSet.getString("password_hash"),
                        resultSet.getInt("failed_attempts"),
                        resultSet.getTimestamp("locked_until") == null
                                ? null : resultSet.getTimestamp("locked_until").toInstant(),
                        resultSet.getString("status")))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId));
    }

    public void recordPasswordFailure(UUID userId, int currentAttempts) {
        int nextAttempts = currentAttempts + 1;
        jdbcTemplate.update("""
                UPDATE user_credential
                SET failed_attempts = ?,
                    locked_until = CASE
                      WHEN ? >= 5 THEN DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)
                      ELSE locked_until
                    END,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND credential_type = 'PAYMENT_PASSWORD'
                """,
                nextAttempts,
                nextAttempts,
                AdminAccountRepository.uuidToBytes(userId));
    }

    public void clearPasswordFailures(UUID userId) {
        jdbcTemplate.update("""
                UPDATE user_credential
                SET failed_attempts = 0, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND credential_type = 'PAYMENT_PASSWORD'
                """, AdminAccountRepository.uuidToBytes(userId));
    }

    public boolean isRealNameVerified(UUID userId) {
        Boolean verified = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM real_name_verification
                  WHERE user_id = ? AND status = 'VERIFIED'
                )
                """, Boolean.class, AdminAccountRepository.uuidToBytes(userId));
        return Boolean.TRUE.equals(verified);
    }

    public boolean insertPaymentCredential(UUID userId, String passwordHash) {
        return jdbcTemplate.update("""
                INSERT IGNORE INTO user_credential (
                  credential_id, user_id, credential_type, password_hash, status,
                  failed_attempts, locked_until, created_at, updated_at
                ) VALUES (?, ?, 'PAYMENT_PASSWORD', ?, 'ACTIVE', 0, NULL,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(
                        com.minipay.identity.application.service.UuidV7.generate()),
                AdminAccountRepository.uuidToBytes(userId), passwordHash) == 1;
    }

    public Optional<AuthorizationRow> findByIdempotency(
            UUID userId, String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT authorization_id, user_id, idempotency_key, request_hash,
                               intent_id, subject_type, subject_id, amount_cent, device_id,
                               token_hash, expires_at, consumed_at
                        FROM payment_authorization
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapAuthorization(resultSet))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId),
                idempotencyKey);
    }

    public Optional<AuthorizationRow> lockByTokenHash(byte[] tokenHash) {
        return jdbcTemplate.query("""
                        SELECT authorization_id, user_id, idempotency_key, request_hash,
                               intent_id, subject_type, subject_id, amount_cent, device_id,
                               token_hash, expires_at, consumed_at
                        FROM payment_authorization
                        WHERE token_hash = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapAuthorization(resultSet))
                        : Optional.empty(),
                tokenHash);
    }

    public void insertAuthorization(
            UUID authorizationId,
            UUID userId,
            String idempotencyKey,
            byte[] requestHash,
            UUID intentId,
            String subjectType,
            UUID subjectId,
            long amountCent,
            String deviceId,
            byte[] tokenHash,
            Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO payment_authorization (
                  authorization_id, user_id, idempotency_key, request_hash, intent_id,
                  subject_type, subject_id, amount_cent, device_id, token_hash,
                  expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(authorizationId),
                AdminAccountRepository.uuidToBytes(userId),
                idempotencyKey,
                requestHash,
                AdminAccountRepository.uuidToBytes(intentId),
                subjectType,
                AdminAccountRepository.uuidToBytes(subjectId),
                amountCent,
                deviceId,
                tokenHash,
                java.sql.Timestamp.from(expiresAt));
    }

    public void consume(UUID authorizationId) {
        int updated = jdbcTemplate.update("""
                UPDATE payment_authorization
                SET consumed_at = UTC_TIMESTAMP(6)
                WHERE authorization_id = ? AND consumed_at IS NULL
                  AND expires_at > UTC_TIMESTAMP(6)
                """, AdminAccountRepository.uuidToBytes(authorizationId));
        if (updated != 1) {
            throw new IllegalStateException("Payment authorization was not consumable");
        }
    }

    private static AuthorizationRow mapAuthorization(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new AuthorizationRow(
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("authorization_id")),
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                resultSet.getString("idempotency_key"),
                resultSet.getBytes("request_hash"),
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("intent_id")),
                resultSet.getString("subject_type"),
                AdminAccountRepository.bytesToUuid(resultSet.getBytes("subject_id")),
                resultSet.getLong("amount_cent"),
                resultSet.getString("device_id"),
                resultSet.getBytes("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("consumed_at") == null
                        ? null : resultSet.getTimestamp("consumed_at").toInstant());
    }

    public record CredentialRow(
            String passwordHash,
            int failedAttempts,
            Instant lockedUntil,
            String status) {
    }

    public record AuthorizationRow(
            UUID authorizationId,
            UUID userId,
            String idempotencyKey,
            byte[] requestHash,
            UUID intentId,
            String subjectType,
            UUID subjectId,
            long amountCent,
            String deviceId,
            byte[] tokenHash,
            Instant expiresAt,
            Instant consumedAt) {
    }
}
