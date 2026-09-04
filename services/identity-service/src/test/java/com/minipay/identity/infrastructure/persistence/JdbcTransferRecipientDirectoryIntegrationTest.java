package com.minipay.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.identity.application.port.TransferRecipientDirectoryPort.RecipientRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
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
class JdbcTransferRecipientDirectoryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static JdbcTemplate jdbc;
    static JdbcTransferRecipientDirectory directory;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        directory = new JdbcTransferRecipientDirectory(jdbc);
    }

    @Test
    void returnsOnlyMaskedProfileDataAndLatestVerifiedLegalName() throws Exception {
        UUID userId = UUID.randomUUID();
        byte[] phoneHash = sha256("recipient-phone-1");
        insertUser(userId, phoneHash, "ACTIVE", "COMPLETED");
        jdbc.update("""
                INSERT INTO real_name_verification (
                    verification_id, user_id, idempotency_key, request_hash,
                    legal_name_masked, legal_name_hash, id_number_masked, id_number_hash,
                    provider, provider_reference, status, failure_code,
                    verified_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED', NULL,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                AdminAccountRepository.uuidToBytes(userId),
                "recipient-lookup-test",
                sha256("request"),
                "张*",
                sha256("legal-name"),
                "410***********0012",
                sha256("id-number"),
                "SANDBOX",
                "recipient-lookup-provider-ref");

        Optional<RecipientRecord> result = directory.findByPhoneHash(phoneHash);

        assertThat(result).contains(new RecipientRecord(
                userId, "小满", "155****7517", "avatars/recipient-1", "张*"));
    }

    @Test
    void excludesInactiveAndIncompleteAccounts() throws Exception {
        byte[] inactiveHash = sha256("recipient-phone-inactive");
        byte[] incompleteHash = sha256("recipient-phone-incomplete");
        insertUser(UUID.randomUUID(), inactiveHash, "DISABLED", "COMPLETED");
        insertUser(UUID.randomUUID(), incompleteHash, "ACTIVE", "PENDING");

        assertThat(directory.findByPhoneHash(inactiveHash)).isEmpty();
        assertThat(directory.findByPhoneHash(incompleteHash)).isEmpty();
    }

    private static void insertUser(UUID userId, byte[] phoneHash, String status, String onboardingStatus) {
        jdbc.update("""
                INSERT INTO user_profile (
                    user_id, login_name, minipay_no, phone_hash, phone_masked,
                    nickname, avatar_object_key, status, onboarding_status,
                    onboarding_completed_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CASE WHEN ? = 'COMPLETED' THEN UTC_TIMESTAMP(6) ELSE NULL END,
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(userId),
                "lookup-" + userId,
                "MP" + userId.toString().replace("-", "").substring(0, 20).toUpperCase(),
                phoneHash,
                "155****7517",
                "小满",
                "avatars/recipient-1",
                status,
                onboardingStatus,
                onboardingStatus);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
