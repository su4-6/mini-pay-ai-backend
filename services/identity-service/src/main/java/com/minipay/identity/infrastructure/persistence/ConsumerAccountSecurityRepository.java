package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.service.AccountSecurityRejectedException;
import com.minipay.identity.application.service.UuidV7;
import com.minipay.identity.application.service.PhoneDisclosureCipher;
import com.minipay.identity.application.service.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConsumerAccountSecurityRepository {
    private final JdbcTemplate jdbc;

    public ConsumerAccountSecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountSecurityView get(UUID userId) {
        return jdbc.query("""
                SELECT u.phone_masked, e.email_masked,
                       EXISTS(
                         SELECT 1 FROM user_credential c
                         WHERE c.user_id = u.user_id
                           AND c.credential_type = 'PAYMENT_PASSWORD'
                           AND c.status = 'ACTIVE'
                       ) AS payment_password_set
                FROM user_profile u
                LEFT JOIN consumer_email_contact e ON e.user_id = u.user_id
                WHERE u.user_id = ? AND u.status = 'ACTIVE'
                """, rs -> rs.next()
                ? new AccountSecurityView(rs.getString("phone_masked"), rs.getString("email_masked"),
                        rs.getBoolean("payment_password_set"))
                : throwRejected("CONSUMER_NOT_FOUND"), bytes(userId));
    }

    public boolean mobileMatches(UUID userId, byte[] phoneHash) {
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM user_profile
                  WHERE user_id = ? AND phone_hash = ? AND status = 'ACTIVE'
                )
                """, Boolean.class, bytes(userId), phoneHash);
        return Boolean.TRUE.equals(matches);
    }

    public Optional<OperationRow> findOperation(UUID userId, String operationType, String idempotencyKey) {
        return jdbc.query("""
                SELECT request_hash, completed_at
                FROM account_security_operation
                WHERE user_id = ? AND operation_type = ? AND idempotency_key = ?
                """, rs -> rs.next()
                ? Optional.of(new OperationRow(rs.getBytes("request_hash"),
                        rs.getTimestamp("completed_at").toInstant()))
                : Optional.empty(), bytes(userId), operationType, idempotencyKey);
    }

    @Transactional
    public void changePhoneAndRevokeSessions(
            UUID userId,
            byte[] phoneHash,
            String maskedPhone,
            PhoneDisclosureCipher.EncryptedPhone encryptedPhone,
            String idempotencyKey,
            byte[] requestHash) {
        try {
            int updated = jdbc.update("""
                    UPDATE user_profile
                    SET phone_hash = ?, phone_masked = ?, phone_ciphertext = ?, phone_nonce = ?,
                        phone_key_id = ?, disclosure_version = disclosure_version + 1,
                        version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE user_id = ? AND status = 'ACTIVE'
                    """, phoneHash, maskedPhone, encryptedPhone.ciphertext(), encryptedPhone.nonce(),
                    encryptedPhone.keyId(), bytes(userId));
            if (updated != 1) throw new AccountSecurityRejectedException("CONSUMER_NOT_FOUND");
        } catch (DuplicateKeyException exception) {
            throw new AccountSecurityRejectedException("MOBILE_ALREADY_BOUND");
        }

        revokeSessions(userId);
        appendDisclosureChanged(userId);
        recordOperation(userId, "PHONE_CHANGE", idempotencyKey, requestHash);
    }

    /** Compatibility overload retained for persistence tests that only exercise session revocation. */
    public void changePhoneAndRevokeSessions(
            UUID userId,
            byte[] phoneHash,
            String maskedPhone,
            String idempotencyKey,
            byte[] requestHash) {
        changePhoneAndRevokeSessionsLegacy(
                userId, phoneHash, maskedPhone, idempotencyKey, requestHash);
    }

    @Transactional
    void changePhoneAndRevokeSessionsLegacy(
            UUID userId,
            byte[] phoneHash,
            String maskedPhone,
            String idempotencyKey,
            byte[] requestHash) {
        try {
            int updated = jdbc.update("""
                    UPDATE user_profile
                    SET phone_hash = ?, phone_masked = ?, version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE user_id = ? AND status = 'ACTIVE'
                    """, phoneHash, maskedPhone, bytes(userId));
            if (updated != 1) throw new AccountSecurityRejectedException("CONSUMER_NOT_FOUND");
        } catch (DuplicateKeyException exception) {
            throw new AccountSecurityRejectedException("MOBILE_ALREADY_BOUND");
        }
        revokeSessions(userId);
        recordOperation(userId, "PHONE_CHANGE", idempotencyKey, requestHash);
    }

    @Transactional
    public void storePhoneDisclosure(
            UUID userId,
            PhoneDisclosureCipher.EncryptedPhone encryptedPhone,
            String idempotencyKey,
            byte[] requestHash) {
        int updated = jdbc.update("""
                UPDATE user_profile
                   SET phone_ciphertext = ?, phone_nonce = ?, phone_key_id = ?,
                       disclosure_version = disclosure_version + 1,
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND status = 'ACTIVE'
                """, encryptedPhone.ciphertext(), encryptedPhone.nonce(), encryptedPhone.keyId(),
                bytes(userId));
        if (updated != 1) throw new AccountSecurityRejectedException("CONSUMER_NOT_FOUND");
        appendDisclosureChanged(userId);
        recordOperation(userId, "PHONE_DISCLOSURE", idempotencyKey, requestHash);
    }

    private void appendDisclosureChanged(UUID userId) {
        UUID eventId = UuidV7.generate();
        jdbc.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
                ) VALUES (?, 'identity.user-disclosure-profile.changed', 'consumer', ?,
                          UTC_TIMESTAMP(6), NULL, 1, ?, 'PENDING', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(eventId), bytes(userId), "{\"userId\":\"" + userId + "\"}");
    }

    private void revokeSessions(UUID userId) {
        List<String> authorizationIds = jdbc.queryForList(
                "SELECT id FROM oauth2_authorization WHERE principal_name = ? FOR UPDATE",
                String.class, userId.toString());
        for (String authorizationId : authorizationIds) {
            jdbc.update("""
                    UPDATE oauth2_refresh_token_history
                    SET active = FALSE, revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
                    WHERE authorization_id = ?
                    """, authorizationId);
            jdbc.update("""
                    INSERT INTO oauth2_refresh_token_family (
                      authorization_id, status, revoke_reason, created_at, updated_at
                    ) VALUES (?, 'REVOKED', 'PHONE_CHANGED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                      status = 'REVOKED',
                      revoke_reason = COALESCE(revoke_reason, 'PHONE_CHANGED'),
                      updated_at = UTC_TIMESTAMP(6)
                    """, authorizationId);
        }
        jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", userId.toString());
    }

    @Transactional
    public void bindEmail(
            UUID userId,
            byte[] emailHmac,
            String maskedEmail,
            String idempotencyKey,
            byte[] requestHash) {
        Optional<UUID> owner = jdbc.query("""
                SELECT user_id FROM consumer_email_contact WHERE email_hmac = ? FOR UPDATE
                """, rs -> rs.next()
                ? Optional.of(AdminAccountRepository.bytesToUuid(rs.getBytes(1)))
                : Optional.empty(), emailHmac);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            throw new AccountSecurityRejectedException("EMAIL_ALREADY_BOUND");
        }
        try {
            jdbc.update("""
                    INSERT INTO consumer_email_contact (
                      user_id, email_hmac, email_masked, verified_at, updated_at
                    ) VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                      email_hmac = VALUES(email_hmac),
                      email_masked = VALUES(email_masked),
                      verified_at = UTC_TIMESTAMP(6),
                      updated_at = UTC_TIMESTAMP(6)
                    """, bytes(userId), emailHmac, maskedEmail);
        } catch (DuplicateKeyException exception) {
            throw new AccountSecurityRejectedException("EMAIL_ALREADY_BOUND");
        }
        recordOperation(userId, "EMAIL_CONFIRM", idempotencyKey, requestHash);
    }

    @Transactional
    public void removeEmail(UUID userId, String idempotencyKey, byte[] requestHash) {
        jdbc.update("DELETE FROM consumer_email_contact WHERE user_id = ?", bytes(userId));
        recordOperation(userId, "EMAIL_DELETE", idempotencyKey, requestHash);
    }

    public Optional<VerificationRow> findVerificationByIssueKey(UUID userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT verification_id, user_id, challenge_id, issue_idempotency_key,
                       issue_request_hash, device_id, issued_at, expires_at, consumed_at
                FROM payment_password_change_verification
                WHERE user_id = ? AND issue_idempotency_key = ?
                """, rs -> rs.next() ? Optional.of(mapVerification(rs)) : Optional.empty(),
                bytes(userId), idempotencyKey);
    }

    public void createVerification(
            UUID verificationId,
            UUID userId,
            String challengeId,
            String idempotencyKey,
            byte[] requestHash,
            String deviceId,
            Instant issuedAt,
            Instant expiresAt) {
        jdbc.update("""
                INSERT INTO payment_password_change_verification (
                  verification_id, user_id, challenge_id, issue_idempotency_key,
                  issue_request_hash, device_id, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, bytes(verificationId), bytes(userId), challengeId, idempotencyKey,
                requestHash, deviceId, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    @Transactional
    public void changePaymentPassword(
            UUID verificationId,
            UUID userId,
            String deviceId,
            String encodedPassword,
            String idempotencyKey,
            byte[] requestHash,
            Instant now) {
        VerificationRow verification = jdbc.query("""
                SELECT verification_id, user_id, challenge_id, issue_idempotency_key,
                       issue_request_hash, device_id, issued_at, expires_at, consumed_at
                FROM payment_password_change_verification
                WHERE verification_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? mapVerification(rs) : null, bytes(verificationId));
        if (verification == null || !verification.userId().equals(userId)) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_INVALID");
        }
        if (!verification.deviceId().equals(deviceId)) {
            throw new AccountSecurityRejectedException("DEVICE_MISMATCH");
        }
        if (!verification.expiresAt().isAfter(now)) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_EXPIRED");
        }
        if (verification.consumedAt() != null) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_USED");
        }

        int updated = jdbc.update("""
                UPDATE user_credential
                SET password_hash = ?, failed_attempts = 0, locked_until = NULL,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND credential_type = 'PAYMENT_PASSWORD' AND status = 'ACTIVE'
                """, encodedPassword, bytes(userId));
        if (updated != 1) {
            throw new AccountSecurityRejectedException("PAYMENT_PASSWORD_NOT_SET");
        }
        jdbc.update("""
                UPDATE payment_authorization
                SET consumed_at = COALESCE(consumed_at, UTC_TIMESTAMP(6))
                WHERE user_id = ? AND consumed_at IS NULL
                """, bytes(userId));
        jdbc.update("""
                UPDATE payment_password_change_verification
                SET consumed_at = UTC_TIMESTAMP(6)
                WHERE verification_id = ? AND consumed_at IS NULL
                """, bytes(verificationId));
        recordOperation(userId, "PAYMENT_PASSWORD_CHANGE", idempotencyKey, requestHash);
    }

    private void recordOperation(
            UUID userId, String operationType, String idempotencyKey, byte[] requestHash) {
        jdbc.update("""
                INSERT INTO account_security_operation (
                  operation_id, user_id, operation_type, idempotency_key,
                  request_hash, completed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UuidV7.generate()), bytes(userId), operationType,
                idempotencyKey, requestHash);
    }

    private VerificationRow mapVerification(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp consumed = rs.getTimestamp("consumed_at");
        return new VerificationRow(
                AdminAccountRepository.bytesToUuid(rs.getBytes("verification_id")),
                AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")),
                rs.getString("challenge_id"),
                rs.getString("issue_idempotency_key"),
                rs.getBytes("issue_request_hash"),
                rs.getString("device_id"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                consumed == null ? null : consumed.toInstant());
    }

    private byte[] bytes(UUID id) {
        return AdminAccountRepository.uuidToBytes(id);
    }

    private AccountSecurityView throwRejected(String code) {
        throw new AccountSecurityRejectedException(code);
    }

    public record AccountSecurityView(
            String maskedMobile, String maskedEmail, boolean paymentPasswordSet) {
    }

    public record OperationRow(byte[] requestHash, Instant completedAt) {
    }

    public record VerificationRow(
            UUID verificationId,
            UUID userId,
            String challengeId,
            String issueIdempotencyKey,
            byte[] issueRequestHash,
            String deviceId,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {
    }
}
