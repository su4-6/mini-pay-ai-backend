package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.port.ExactFriendRecipientDirectoryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExactFriendRecipientDirectory implements ExactFriendRecipientDirectoryPort {
    private final JdbcTemplate jdbc;

    public JdbcExactFriendRecipientDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FriendRecipientRecord> findExactMatches(
            UUID ownerUserId, String nickname, byte[] legalNameHash, int limit) {
        return jdbc.query("""
                SELECT up.user_id, up.nickname, up.phone_masked, current_name.legal_name_masked,
                       CASE WHEN up.nickname = ? THEN 1 ELSE 0 END AS nickname_matched,
                       CASE WHEN current_name.legal_name_hash = ? THEN 1 ELSE 0 END AS legal_name_matched
                FROM friend_relation fr
                JOIN user_profile up ON up.user_id = fr.friend_id
                LEFT JOIN (
                    SELECT ranked.user_id, ranked.legal_name_masked, ranked.legal_name_hash
                    FROM (
                        SELECT rv.user_id, rv.legal_name_masked, rv.legal_name_hash,
                               ROW_NUMBER() OVER (
                                   PARTITION BY rv.user_id
                                   ORDER BY rv.verified_at DESC, rv.created_at DESC
                               ) AS recipient_rank
                        FROM real_name_verification rv
                        WHERE rv.status = 'VERIFIED'
                    ) ranked
                    WHERE ranked.recipient_rank = 1
                ) current_name ON current_name.user_id = up.user_id
                WHERE fr.user_id = ?
                  AND fr.deleted_at IS NULL
                  AND up.status = 'ACTIVE'
                  AND (up.nickname = ? OR current_name.legal_name_hash = ?)
                ORDER BY up.nickname, up.user_id
                LIMIT ?
                """, (rs, row) -> new FriendRecipientRecord(
                        AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")),
                        rs.getString("nickname"), rs.getString("phone_masked"),
                        rs.getString("legal_name_masked"),
                        rs.getBoolean("nickname_matched"), rs.getBoolean("legal_name_matched")),
                nickname, legalNameHash, AdminAccountRepository.uuidToBytes(ownerUserId),
                nickname, legalNameHash, Math.min(Math.max(limit, 1), MAX_MATCHES));
    }

    private static final int MAX_MATCHES = 10;
}
