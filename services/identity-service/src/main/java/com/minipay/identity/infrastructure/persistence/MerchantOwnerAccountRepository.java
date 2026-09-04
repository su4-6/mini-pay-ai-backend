package com.minipay.identity.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.service.UuidV7;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商户拥有者账户（BD 代建 / 入驻审核通过时由 payment-service 经内部接口调用）。
 * 复用登录体系：同一 userId 追加 merchant_owner 角色，不新建独立账号。
 */
@Repository
public class MerchantOwnerAccountRepository {
    private static final String MERCHANT_OWNER_ROLE = "merchant_owner";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MerchantOwnerAccountRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<MerchantOwnerAccount> findByPhoneHash(byte[] phoneHash) {
        return jdbcTemplate.query("""
                        SELECT user_id, login_name, status, credential_type
                          FROM user_profile
                         WHERE phone_hash = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new MerchantOwnerAccount(
                            AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                            resultSet.getString("login_name"),
                            resultSet.getString("status"),
                            resultSet.getString("credential_type")));
                },
                phoneHash);
    }

    @Transactional
    public MerchantOwnerAccount createSmsOnly(
            byte[] phoneHash, String displayName, String traceId) {
        UUID candidateId = UuidV7.generate();
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO user_profile (
                  user_id, login_name, minipay_no, phone_hash, nickname, status,
                  credential_type, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'SMS_ONLY', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(candidateId),
                "merchant_" + candidateId.toString().replace("-", ""),
                "MP" + candidateId.toString().replace("-", "")
                        .substring(0, 20).toUpperCase(Locale.ROOT),
                phoneHash,
                displayName == null || displayName.isBlank() ? "商户" : displayName);
        MerchantOwnerAccount account = findByPhoneHash(phoneHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Merchant owner registration did not converge"));
        if (inserted == 1) {
            appendUserOpened(account.userId(), traceId);
        }
        return account;
    }

    public void addMerchantOwnerRole(UUID userId) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO user_role (user_id, role_code, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(userId), MERCHANT_OWNER_ROLE);
    }

    private void appendUserOpened(UUID userId, String traceId) {
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

    public record MerchantOwnerAccount(UUID userId, String loginName, String status,
                                       String credentialType) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
