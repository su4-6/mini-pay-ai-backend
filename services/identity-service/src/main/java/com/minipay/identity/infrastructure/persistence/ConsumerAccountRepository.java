package com.minipay.identity.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.service.PhoneDisclosureCipher;
import com.minipay.identity.application.service.UuidV7;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConsumerAccountRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PhoneDisclosureCipher phoneCipher;

    public ConsumerAccountRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PhoneDisclosureCipher phoneCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.phoneCipher = phoneCipher;
    }

    @Transactional
    public ConsumerPrincipal findOrCreate(byte[] phoneHash, String traceId) {
        Optional<ConsumerPrincipal> existing = findByPhoneHash(phoneHash);
        if (existing.isEmpty()) {
            UUID candidateId = UuidV7.generate();
            jdbcTemplate.update("""
                    INSERT IGNORE INTO user_profile (
                      user_id, login_name, minipay_no, phone_hash, nickname, status, version,
                      created_at, updated_at
                    ) VALUES (?, ?, ?, ?, '米灵用户', 'ACTIVE', 0,
                              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """,
                    AdminAccountRepository.uuidToBytes(candidateId),
                    "consumer_" + candidateId.toString().replace("-", ""),
                    "MP" + candidateId.toString().replace("-", "")
                            .substring(0, 20).toUpperCase(java.util.Locale.ROOT),
                    phoneHash);
        }
        ConsumerPrincipal principal = findByPhoneHashForUpdate(phoneHash)
                .map(this::requireActive)
                .orElseThrow(() -> new IllegalStateException("Consumer registration did not converge"));
        ensureUserOpened(principal.userId(), traceId);
        return principal;
    }

    public Optional<ConsumerPrincipal> findByPhoneHash(byte[] phoneHash) {
        return queryByPhoneHash(phoneHash, false);
    }

    @Transactional
    public void recordVerifiedPhone(UUID userId, String mobile, String maskedPhone) {
        PhoneDisclosureCipher.EncryptedPhone encrypted = phoneCipher.encrypt(userId, mobile);
        int updated = jdbcTemplate.update("""
                UPDATE user_profile
                SET phone_masked = ?, phone_ciphertext = ?, phone_nonce = ?, phone_key_id = ?,
                    disclosure_version = disclosure_version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND status = 'ACTIVE'
                """, maskedPhone, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(),
                AdminAccountRepository.uuidToBytes(userId));
        if (updated != 1) throw new ConsumerAccountDisabledException();
    }

    private Optional<ConsumerPrincipal> findByPhoneHashForUpdate(byte[] phoneHash) {
        return queryByPhoneHash(phoneHash, true);
    }

    private Optional<ConsumerPrincipal> queryByPhoneHash(byte[] phoneHash, boolean lockingRead) {
        return jdbcTemplate.query("""
                        SELECT u.user_id, u.nickname, u.status,
                               EXISTS(
                                 SELECT 1 FROM user_credential c
                                 WHERE c.user_id = u.user_id
                                   AND c.credential_type = 'PAYMENT_PASSWORD'
                                   AND c.status = 'ACTIVE'
                               ) AS pay_password_set,
                               EXISTS(
                                 SELECT 1 FROM user_role r
                                 WHERE r.user_id = u.user_id
                                   AND r.role_code = 'merchant_owner'
                               ) AS merchant_owner,
                               u.onboarding_status,
                               COALESCE((
                                 SELECT r.status FROM real_name_verification r
                                 WHERE r.user_id = u.user_id
                                 ORDER BY (r.status = 'VERIFIED') DESC, r.updated_at DESC
                                 LIMIT 1
                               ), 'UNVERIFIED') AS real_name_status
                        FROM user_profile u
                        WHERE u.phone_hash = ?
                        """ + (lockingRead ? " FOR UPDATE" : ""),
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    if (!"ACTIVE".equals(resultSet.getString("status"))) {
                        throw new ConsumerAccountDisabledException();
                    }
                    return Optional.of(new ConsumerPrincipal(
                            AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                            resultSet.getString("nickname"),
                            resultSet.getBoolean("pay_password_set"),
                            "COMPLETED".equals(resultSet.getString("onboarding_status")),
                            resultSet.getString("real_name_status"),
                            resultSet.getBoolean("merchant_owner")));
                },
                phoneHash);
    }

    public Optional<ConsumerPrincipal> findActive(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT u.user_id, u.nickname, u.onboarding_status,
                               EXISTS(
                                 SELECT 1 FROM user_credential c
                                 WHERE c.user_id = u.user_id
                                   AND c.credential_type = 'PAYMENT_PASSWORD'
                                   AND c.status = 'ACTIVE'
                               ) AS pay_password_set,
                               EXISTS(
                                 SELECT 1 FROM user_role r
                                 WHERE r.user_id = u.user_id
                                   AND r.role_code = 'merchant_owner'
                               ) AS merchant_owner,
                               COALESCE((
                                 SELECT r.status FROM real_name_verification r
                                 WHERE r.user_id = u.user_id
                                 ORDER BY (r.status = 'VERIFIED') DESC, r.updated_at DESC
                                 LIMIT 1
                               ), 'UNVERIFIED') AS real_name_status
                        FROM user_profile u
                        WHERE u.user_id = ? AND u.status = 'ACTIVE'
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ConsumerPrincipal(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("nickname"),
                        resultSet.getBoolean("pay_password_set"),
                        "COMPLETED".equals(resultSet.getString("onboarding_status")),
                        resultSet.getString("real_name_status"),
                        resultSet.getBoolean("merchant_owner")))
                        : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId));
    }

    public String requireVerifiedMobile(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT phone_ciphertext, phone_nonce, phone_key_id
                        FROM user_profile
                        WHERE user_id = ? AND status = 'ACTIVE'
                          AND phone_ciphertext IS NOT NULL
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new ConsumerAccountDisabledException();
                    }
                    return phoneCipher.decrypt(userId, resultSet.getBytes("phone_ciphertext"),
                            resultSet.getBytes("phone_nonce"), resultSet.getString("phone_key_id"));
                }, AdminAccountRepository.uuidToBytes(userId));
    }

    private ConsumerPrincipal requireActive(ConsumerPrincipal principal) {
        return principal;
    }

    private void ensureUserOpened(UUID userId, String traceId) {
        List<Integer> existing = jdbcTemplate.query("""
                SELECT 1
                FROM outbox_event
                WHERE event_type = 'identity.user.opened' AND aggregate_id = ?
                LIMIT 1 FOR UPDATE
                """, (resultSet, row) -> 1, AdminAccountRepository.uuidToBytes(userId));
        if (!existing.isEmpty()) {
            return;
        }
        UUID eventId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
                ) VALUES (?, 'identity.user.opened', 'user', ?, UTC_TIMESTAMP(6),
                          ?, 1, ?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(eventId),
                AdminAccountRepository.uuidToBytes(userId),
                traceId,
                json(Map.of("userId", userId.toString())));
    }

    private String json(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize user opened event", exception);
        }
    }

    public static final class ConsumerAccountDisabledException extends RuntimeException {
    }
}
