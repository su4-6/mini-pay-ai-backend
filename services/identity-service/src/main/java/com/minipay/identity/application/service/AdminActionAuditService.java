package com.minipay.identity.application.service;

import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminActionAuditService {
    private final JdbcTemplate jdbc;

    public AdminActionAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actor, String action, String targetType, String targetId,
            String result, String reason, String requestId, String clientAddress,
            String userAgent) {
        jdbc.update("""
                INSERT INTO admin_action_audit(
                  audit_id,actor_user_id,action_code,target_type,target_id,result_code,
                  reason,request_id,client_ip_digest,user_agent_digest,occurred_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(UuidV7.generate()),
                actor == null ? null : AdminAccountRepository.uuidToBytes(actor), action,
                targetType, targetId, result, reason, requestId, digest(clientAddress),
                digest(userAgent));
    }

    private static byte[] digest(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
