package com.minipay.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.identity.application.port.ExactFriendRecipientDirectoryPort.FriendRecipientRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcExactFriendRecipientDirectoryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static JdbcTemplate jdbc;
    static JdbcExactFriendRecipientDirectory directory;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        directory = new JdbcExactFriendRecipientDirectory(jdbc);
    }

    @Test
    void resolvesFriendByNicknameOnMySql84() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID friendId = UUID.randomUUID();
        insertUser(ownerId, "owner");
        insertUser(friendId, "小明");
        jdbc.update("""
                INSERT INTO friend_relation (id, user_id, friend_id, created_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(ownerId), bytes(friendId));
        jdbc.update("""
                INSERT INTO real_name_verification (
                    verification_id, user_id, idempotency_key, request_hash,
                    legal_name_masked, legal_name_hash, id_number_masked, id_number_hash,
                    provider, provider_reference, status, failure_code,
                    verified_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED', NULL,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(friendId), "friend-rank-test",
                sha256("request"), "冯*冰", sha256("冯开冰"), "410***********0012",
                sha256("id-number"), "SANDBOX", "friend-rank-provider-ref");

        assertThat(directory.findExactMatches(ownerId, "小明", sha256("小明"), 10))
                .containsExactly(new FriendRecipientRecord(
                        friendId, "小明", "155****7517", "冯*冰", true, false));
    }

    private static void insertUser(UUID userId, String nickname) throws Exception {
        jdbc.update("""
                INSERT INTO user_profile (
                    user_id, login_name, minipay_no, phone_hash, phone_masked,
                    nickname, avatar_object_key, status, onboarding_status,
                    onboarding_completed_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, '155****7517', ?, NULL, 'ACTIVE', 'COMPLETED',
                          UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(userId), "friend-" + userId,
                "MP" + userId.toString().replace("-", "").substring(0, 20).toUpperCase(),
                sha256("phone-" + userId), nickname);
    }

    private static byte[] bytes(UUID value) {
        return AdminAccountRepository.uuidToBytes(value);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
