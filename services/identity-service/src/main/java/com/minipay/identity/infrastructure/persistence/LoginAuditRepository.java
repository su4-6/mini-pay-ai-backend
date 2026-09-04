package com.minipay.identity.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoginAuditRepository {
    private final JdbcTemplate jdbcTemplate;
    private final byte[] auditPepper;

    public LoginAuditRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${minipay.identity.audit-hash-pepper}") String auditPepper) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditPepper = auditPepper.getBytes(StandardCharsets.UTF_8);
    }

    public void appendLogin(UUID userId, String identifier, String method, String result,
                            String clientAddress, String userAgent, String requestId) {
        append("LOGIN", userId, identifier, method, result, clientAddress, userAgent, requestId);
    }

    public void appendLogout(UUID userId, String identifier, String clientAddress,
                             String userAgent, String requestId) {
        append("LOGOUT", userId, identifier, "OIDC", "SUCCESS",
                clientAddress, userAgent, requestId);
    }

    private void append(String eventType, UUID userId, String identifier, String method,
                        String result, String clientAddress, String userAgent, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO login_audit (
                  audit_id, event_type, user_id, login_identifier_hash, authentication_method,
                  result_code, client_address_hash, user_agent_hash, request_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                eventType,
                userId == null ? null : AdminAccountRepository.uuidToBytes(userId),
                digest(identifier),
                method,
                result,
                digest(clientAddress),
                digest(userAgent),
                requestId);
    }

    public List<LoginAuditItem> findPage(int page, int size) {
        return jdbcTemplate.query("""
                SELECT a.audit_id, a.occurred_at, a.authentication_method, a.result_code,
                       a.request_id, u.nickname
                FROM login_audit a
                LEFT JOIN user_profile u ON u.user_id = a.user_id
                WHERE a.event_type = 'LOGIN'
                ORDER BY a.occurred_at DESC
                LIMIT ? OFFSET ?
                """,
                (resultSet, rowNumber) -> new LoginAuditItem(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("audit_id")).toString(),
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        resultSet.getString("authentication_method"),
                        resultSet.getString("result_code"),
                        resultSet.getString("nickname"),
                        resultSet.getString("request_id")),
                size,
                page * size);
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(audit_id) FROM login_audit WHERE event_type = 'LOGIN'",
                Long.class);
        return value == null ? 0 : value;
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(auditPepper, "HmacSHA256"));
            return mac.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create audit digest", exception);
        }
    }

    public record LoginAuditItem(
            String auditId,
            Instant occurredAt,
            String authenticationMethod,
            String result,
            String displayName,
            String requestId) {
    }
}
