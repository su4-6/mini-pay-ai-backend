package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.domain.model.AdminPrincipal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminAccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AdminAccount> findByPhoneHash(byte[] phoneHash) {
        String sql = """
                SELECT u.user_id, u.nickname, u.status AS user_status,
                       COALESCE(c.password_hash, '') AS password_hash,
                       COALESCE(c.status, 'ACTIVE') AS credential_status,
                       COALESCE(c.failed_attempts, 0) AS failed_attempts, c.locked_until,
                       (SELECT GROUP_CONCAT(r.role_code ORDER BY r.role_code)
                          FROM user_role r WHERE r.user_id = u.user_id) AS roles
                FROM user_profile u
                LEFT JOIN user_credential c
                  ON c.user_id = u.user_id AND c.credential_type = 'LOGIN_PASSWORD'
                WHERE u.phone_hash = ?
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapAccount, phoneHash));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<AdminPrincipal> findPrincipal(UUID userId) {
        String sql = """
                SELECT u.user_id, u.nickname, COALESCE(c.password_hash, '') AS password_hash,
                       GROUP_CONCAT(r.role_code ORDER BY r.role_code) AS roles
                FROM user_profile u
                LEFT JOIN user_credential c
                  ON c.user_id = u.user_id AND c.credential_type = 'LOGIN_PASSWORD'
                JOIN user_role r ON r.user_id = u.user_id
                WHERE u.user_id = ? AND u.status = 'ACTIVE'
                  AND (c.status IS NULL OR c.status = 'ACTIVE')
                  AND r.role_code IN ('platform_admin','system_super_admin','system_account_admin','system_auditor')
                GROUP BY u.user_id, u.nickname, c.password_hash
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    sql,
                    (resultSet, rowNumber) -> new AdminPrincipal(
                            bytesToUuid(resultSet.getBytes("user_id")),
                            resultSet.getString("nickname"),
                            resultSet.getString("password_hash"),
                            roles(resultSet.getString("roles"))),
                    uuidToBytes(userId)));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public void recordPasswordFailure(UUID userId) {
        jdbcTemplate.update("""
                UPDATE user_credential
                SET failed_attempts = failed_attempts + 1,
                    locked_until = CASE
                      WHEN failed_attempts + 1 >= 5 THEN DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE)
                      ELSE locked_until
                    END,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND credential_type = 'LOGIN_PASSWORD'
                """, uuidToBytes(userId));
    }

    public void resetFailures(UUID userId) {
        jdbcTemplate.update("""
                UPDATE user_credential
                SET failed_attempts = 0, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND credential_type = 'LOGIN_PASSWORD'
                """, uuidToBytes(userId));
    }

    @Transactional
    public void saveLoginPassword(UUID userId, String passwordHash) {
        int updated = jdbcTemplate.update("""
                UPDATE user_credential
                   SET password_hash = ?, status = 'ACTIVE', failed_attempts = 0,
                       locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND credential_type = 'LOGIN_PASSWORD'
                """, passwordHash, uuidToBytes(userId));
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO user_credential (
                      credential_id, user_id, credential_type, password_hash, status,
                      failed_attempts, locked_until, created_at, updated_at
                    ) VALUES (?, ?, 'LOGIN_PASSWORD', ?, 'ACTIVE', 0, NULL,
                              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uuidToBytes(com.minipay.identity.application.service.UuidV7.generate()),
                    uuidToBytes(userId), passwordHash);
        }
        jdbcTemplate.update("""
                UPDATE user_profile
                   SET credential_type = 'PASSWORD', version = version + 1,
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ?
                """, uuidToBytes(userId));
    }

    private AdminAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new AdminAccount(
                bytesToUuid(resultSet.getBytes("user_id")),
                resultSet.getString("nickname"),
                resultSet.getString("password_hash"),
                resultSet.getString("user_status"),
                resultSet.getString("credential_status"),
                resultSet.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                roles(resultSet.getString("roles")));
    }

    public record AdminAccount(
            UUID userId,
            String displayName,
            String passwordHash,
            String userStatus,
            String credentialStatus,
            int failedAttempts,
            Instant lockedUntil,
            List<String> roles) {
        public AdminAccount(UUID userId, String displayName, String passwordHash,
                String userStatus, String credentialStatus, int failedAttempts,
                Instant lockedUntil, boolean platformAdmin) {
            this(userId, displayName, passwordHash, userStatus, credentialStatus,
                    failedAttempts, lockedUntil,
                    platformAdmin ? List.of("platform_admin") : List.of());
        }

        public boolean active() {
            return "ACTIVE".equals(userStatus) && "ACTIVE".equals(credentialStatus);
        }

        public boolean locked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        public AdminPrincipal principal() {
            return new AdminPrincipal(userId, displayName, passwordHash, roles);
        }

        public boolean backoffice() { return roles.stream().anyMatch(AdminAccountRepository::backofficeRole); }
    }

    private static List<String> roles(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private static boolean backofficeRole(String role) {
        return role.equals("platform_admin") || role.equals("system_super_admin")
                || role.equals("system_account_admin") || role.equals("system_auditor");
    }

    public static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    public static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
