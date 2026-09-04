package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.service.UuidV7;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Identity-owned merchant portal password for a user account, not for a merchant. */
@Repository
public class MerchantLoginCredentialRepository {
    private static final String TYPE = "MERCHANT_LOGIN_PASSWORD";
    private final JdbcTemplate jdbc;

    public MerchantLoginCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM user_credential
                 WHERE user_id = ? AND credential_type = ?
                """, Integer.class, AdminAccountRepository.uuidToBytes(userId), TYPE);
        return count != null && count > 0;
    }

    public Optional<Credential> lock(UUID userId) {
        return jdbc.query("""
                        SELECT password_hash, status, failed_attempts, locked_until, updated_at
                          FROM user_credential
                         WHERE user_id = ? AND credential_type = ? FOR UPDATE
                        """,
                result -> result.next()
                        ? Optional.of(new Credential(result.getString("password_hash"),
                                result.getString("status"), result.getInt("failed_attempts"),
                                result.getTimestamp("locked_until") == null ? null
                                        : result.getTimestamp("locked_until").toInstant(),
                                result.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId), TYPE);
    }

    public void save(UUID userId, String passwordHash) {
        int updated = jdbc.update("""
                UPDATE user_credential
                   SET password_hash = ?, status = 'ACTIVE', failed_attempts = 0,
                       locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND credential_type = ?
                """, passwordHash, AdminAccountRepository.uuidToBytes(userId), TYPE);
        if (updated == 1) {
            return;
        }
        jdbc.update("""
                INSERT INTO user_credential (
                  credential_id, user_id, credential_type, password_hash, status,
                  failed_attempts, locked_until, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(UuidV7.generate()),
                AdminAccountRepository.uuidToBytes(userId), TYPE, passwordHash);
    }

    public void recordFailure(UUID userId, int currentAttempts) {
        int next = currentAttempts + 1;
        jdbc.update("""
                UPDATE user_credential
                   SET failed_attempts = ?,
                       locked_until = CASE WHEN ? >= 5
                         THEN DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)
                         ELSE locked_until END,
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND credential_type = ?
                """, next, next, AdminAccountRepository.uuidToBytes(userId), TYPE);
    }

    public void clearFailures(UUID userId) {
        jdbc.update("""
                UPDATE user_credential
                   SET failed_attempts = 0, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND credential_type = ?
                """, AdminAccountRepository.uuidToBytes(userId), TYPE);
    }

    public record Credential(
            String passwordHash,
            String status,
            int failedAttempts,
            Instant lockedUntil,
            Instant changedAt) {
    }
}
