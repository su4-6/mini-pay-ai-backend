package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.port.TransferRecipientDirectoryPort;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransferRecipientDirectory implements TransferRecipientDirectoryPort {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTransferRecipientDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<RecipientRecord> findByPhoneHash(byte[] phoneHash) {
        return jdbcTemplate.query("""
                        SELECT u.user_id, u.nickname, u.phone_masked, u.avatar_object_key,
                               (
                                 SELECT r.legal_name_masked
                                 FROM real_name_verification r
                                 WHERE r.user_id = u.user_id AND r.status = 'VERIFIED'
                                 ORDER BY r.verified_at DESC, r.created_at DESC
                                 LIMIT 1
                               ) AS legal_name_masked
                        FROM user_profile u
                        WHERE u.phone_hash = ?
                          AND u.status = 'ACTIVE'
                          AND u.onboarding_status = 'COMPLETED'
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new RecipientRecord(
                        AdminAccountRepository.bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("nickname"),
                        resultSet.getString("phone_masked"),
                        resultSet.getString("avatar_object_key"),
                        resultSet.getString("legal_name_masked")))
                        : Optional.empty(),
                phoneHash);
    }
}
