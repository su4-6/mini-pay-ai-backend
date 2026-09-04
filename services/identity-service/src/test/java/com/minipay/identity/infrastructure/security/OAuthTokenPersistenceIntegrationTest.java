package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.service.AccountSecurityRejectedException;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.support.TransactionTemplate;

@Testcontainers(disabledWithoutDocker = true)
class OAuthTokenPersistenceIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static JdbcTemplate jdbc;
    static JdbcRegisteredClientRepository clients;
    static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        clients = new JdbcRegisteredClientRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void storesOnlyDigestsAndRevokesRefreshFamilyOnReuse() {
        RegisteredClient client = RegisteredClient.withId("integration-client-id")
                .clientId("integration-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .scope("ops.portal")
                .build();
        clients.save(client);
        Instant now = Instant.now();
        HashMap<String, Object> idTokenClaims = new HashMap<>();
        idTokenClaims.put("sub", "admin");
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("019fb3d0-0000-7000-8000-000000000001")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("ops.portal"))
                .token(new OAuth2AuthorizationCode("raw-code", now, now.plusSeconds(60)))
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "raw-access",
                        now,
                        now.plusSeconds(600),
                        Set.of("ops.portal")))
                .refreshToken(new OAuth2RefreshToken("raw-refresh", now, now.plusSeconds(3600)))
                .token(new OidcIdToken(
                                "raw-id-token",
                                now,
                                now.plusSeconds(600),
                                idTokenClaims),
                        metadata -> metadata.put(
                                OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                                new HashMap<>(idTokenClaims)))
                .build();
        DigestingOAuth2AuthorizationService service = new DigestingOAuth2AuthorizationService(
                new JdbcOAuth2AuthorizationService(jdbc, clients),
                jdbc,
                "integration-token-pepper");

        service.save(authorization);

        List<byte[]> columns = jdbc.queryForObject("""
                SELECT authorization_code_value, access_token_value,
                       refresh_token_value, oidc_id_token_value
                FROM oauth2_authorization
                WHERE id = ?
                """, (resultSet, rowNumber) -> List.of(
                resultSet.getBytes(1), resultSet.getBytes(2),
                resultSet.getBytes(3), resultSet.getBytes(4)), authorization.getId());
        String persisted = columns.stream()
                .map(value -> new String(value, StandardCharsets.ISO_8859_1))
                .reduce("", String::concat);
        assertThat(persisted)
                .doesNotContain("raw-code")
                .doesNotContain("raw-access")
                .doesNotContain("raw-refresh")
                .doesNotContain("raw-id-token");

        OAuth2Authorization first =
                service.findByToken("raw-refresh", OAuth2TokenType.REFRESH_TOKEN);
        assertThat(first).isNotNull();
        assertThat(first.getRefreshToken().getToken().getTokenValue()).isEqualTo("raw-refresh");
        assertThat(service.findByToken("raw-refresh", OAuth2TokenType.REFRESH_TOKEN)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM oauth2_refresh_token_family
                WHERE authorization_id = ?
                """, String.class, authorization.getId())).isEqualTo("REVOKED");
    }

    @Test
    void hashesAuditIdentifiersAndExcludesLogoutFromLoginPage() {
        LoginAuditRepository audits = new LoginAuditRepository(jdbc, "independent-audit-pepper");
        UUID userId = UUID.randomUUID();
        audits.appendLogin(
                userId,
                "13800138000",
                "PASSWORD",
                "SUCCESS",
                "203.0.113.10",
                "integration-user-agent",
                "login-request");
        audits.appendLogout(
                userId,
                userId.toString(),
                "203.0.113.10",
                "integration-user-agent",
                "logout-request");

        assertThat(audits.count()).isEqualTo(1);
        assertThat(audits.findPage(0, 20))
                .singleElement()
                .extracting(LoginAuditRepository.LoginAuditItem::requestId)
                .isEqualTo("login-request");

        List<byte[]> hashes = jdbc.query("""
                SELECT login_identifier_hash, client_address_hash, user_agent_hash
                FROM login_audit
                """, (resultSet, rowNumber) -> {
            byte[] combined = new byte[96];
            System.arraycopy(resultSet.getBytes(1), 0, combined, 0, 32);
            System.arraycopy(resultSet.getBytes(2), 0, combined, 32, 32);
            System.arraycopy(resultSet.getBytes(3), 0, combined, 64, 32);
            return combined;
        });
        String persisted = hashes.stream()
                .map(value -> new String(value, StandardCharsets.ISO_8859_1))
                .reduce("", String::concat);
        assertThat(persisted)
                .doesNotContain("13800138000")
                .doesNotContain("203.0.113.10")
                .doesNotContain("integration-user-agent")
                .doesNotContain(userId.toString());
    }

    @Test
    void concurrentRefreshReuseRevokesFamilyAndRemovesAuthorization() throws Exception {
        RegisteredClient client = RegisteredClient.withId("concurrent-client-id")
                .clientId("concurrent-client")
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("ops.portal")
                .build();
        clients.save(client);
        Instant now = Instant.now();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("019fb3d0-0000-7000-8000-000000000002")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("ops.portal"))
                .refreshToken(new OAuth2RefreshToken(
                        "concurrent-raw-refresh", now, now.plusSeconds(3600)))
                .build();
        DigestingOAuth2AuthorizationService service = new DigestingOAuth2AuthorizationService(
                new JdbcOAuth2AuthorizationService(jdbc, clients),
                jdbc,
                "integration-token-pepper");
        transactions.executeWithoutResult(status -> service.save(authorization));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<OAuth2Authorization> refreshAttempt = () -> {
                ready.countDown();
                start.await();
                return transactions.execute(status ->
                        service.findByToken(
                                "concurrent-raw-refresh",
                                OAuth2TokenType.REFRESH_TOKEN));
            };
            Future<OAuth2Authorization> first = executor.submit(refreshAttempt);
            Future<OAuth2Authorization> second = executor.submit(refreshAttempt);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get() == null, second.get() == null))
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM oauth2_refresh_token_family
                WHERE authorization_id = ?
                """, String.class, authorization.getId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM oauth2_authorization
                WHERE id = ?
                """, Integer.class, authorization.getId())).isZero();
    }

    @Test
    void concurrentFirstLoginCreatesOneConsumerAndOneOutboxEvent() throws Exception {
        ConsumerAccountRepository accounts =
                new ConsumerAccountRepository(jdbc, new ObjectMapper(),
                        new com.minipay.identity.application.service.PhoneDisclosureCipher(
                                "test-phone-disclosure-key-at-least-32-characters", "test"));
        byte[] phoneHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("consumer-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ConsumerPrincipal> register = () -> {
                ready.countDown();
                start.await();
                return transactions.execute(status ->
                        accounts.findOrCreate(phoneHash, UUID.randomUUID().toString()));
            };
            Future<ConsumerPrincipal> first = executor.submit(register);
            Future<ConsumerPrincipal> second = executor.submit(register);
            ready.await();
            start.countDown();

            assertThat(first.get().userId()).isEqualTo(second.get().userId());
        }

        byte[] userId = jdbc.queryForObject("""
                SELECT user_id
                FROM user_profile
                WHERE phone_hash = ?
                """, byte[].class, phoneHash);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM user_profile
                WHERE phone_hash = ?
                """, Integer.class, phoneHash)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'identity.user.opened'
                """, Integer.class, userId)).isOne();
        assertThat(AdminAccountRepository.bytesToUuid(userId).version()).isEqualTo(7);
    }

    @Test
    void changingPhoneRevokesEveryRefreshFamilyForTheConsumer() throws Exception {
        UUID userId = UUID.randomUUID();
        byte[] originalPhoneHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("phone-old-" + userId).getBytes(StandardCharsets.UTF_8));
        byte[] newPhoneHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("phone-new-" + userId).getBytes(StandardCharsets.UTF_8));
        insertActiveUser(userId, originalPhoneHash);

        RegisteredClient client = RegisteredClient.withId("phone-change-" + userId)
                .clientId("phone-change-client-" + userId)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("profile.read")
                .build();
        clients.save(client);
        Instant now = Instant.now();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(userId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile.read"))
                .refreshToken(new OAuth2RefreshToken(
                        "phone-change-refresh-" + userId, now, now.plusSeconds(3600)))
                .build();
        DigestingOAuth2AuthorizationService authorizations = new DigestingOAuth2AuthorizationService(
                new JdbcOAuth2AuthorizationService(jdbc, clients), jdbc, "integration-token-pepper");
        transactions.executeWithoutResult(status -> authorizations.save(authorization));

        ConsumerAccountSecurityRepository repository = new ConsumerAccountSecurityRepository(jdbc);
        byte[] requestHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("phone-change-request-" + userId).getBytes(StandardCharsets.UTF_8));
        transactions.executeWithoutResult(status -> repository.changePhoneAndRevokeSessions(
                userId, newPhoneHash, "139****0000", "phone-change-idempotency-" + userId,
                requestHash));

        assertThat(jdbc.queryForObject(
                "SELECT phone_hash FROM user_profile WHERE user_id = ?", byte[].class,
                AdminAccountRepository.uuidToBytes(userId))).isEqualTo(newPhoneHash);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?",
                Integer.class, userId.toString())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM oauth2_refresh_token_family WHERE authorization_id = ?",
                String.class, authorization.getId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT active FROM oauth2_refresh_token_history WHERE authorization_id = ?",
                Boolean.class, authorization.getId())).isFalse();
    }

    @Test
    void paymentPasswordChangeConsumesVerificationAndOutstandingAuthorizations() throws Exception {
        UUID userId = UUID.randomUUID();
        byte[] phoneHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("payment-phone-" + userId).getBytes(StandardCharsets.UTF_8));
        insertActiveUser(userId, phoneHash);
        jdbc.update("""
                INSERT INTO user_credential (
                  credential_id, user_id, credential_type, password_hash, status,
                  failed_attempts, locked_until, created_at, updated_at
                ) VALUES (?, ?, 'PAYMENT_PASSWORD', 'old-hash', 'ACTIVE', 4,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                AdminAccountRepository.uuidToBytes(userId));
        jdbc.update("""
                INSERT INTO payment_authorization (
                  authorization_id, user_id, intent_id, subject_type, subject_id,
                  amount_cent, device_id, token_hash, expires_at, consumed_at, created_at
                ) VALUES (?, ?, ?, 'TRANSFER_INTENT', ?, 100, 'device-1', ?,
                          DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE), NULL, UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                AdminAccountRepository.uuidToBytes(userId),
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                AdminAccountRepository.uuidToBytes(UUID.randomUUID()),
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(("payment-auth-" + userId).getBytes(StandardCharsets.UTF_8)));

        ConsumerAccountSecurityRepository repository = new ConsumerAccountSecurityRepository(jdbc);
        UUID verificationId = UUID.randomUUID();
        byte[] issueHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("issue-" + userId).getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        repository.createVerification(verificationId, userId, "challenge-" + userId,
                "verification-idempotency-" + userId, issueHash, "device-1", now,
                now.plusSeconds(300));
        byte[] changeHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("change-" + userId).getBytes(StandardCharsets.UTF_8));

        transactions.executeWithoutResult(status -> repository.changePaymentPassword(
                verificationId, userId, "device-1", "new-argon2-hash",
                "change-idempotency-" + userId, changeHash, now.plusSeconds(1)));

        assertThat(jdbc.queryForMap("""
                SELECT password_hash, failed_attempts, locked_until
                FROM user_credential
                WHERE user_id = ? AND credential_type = 'PAYMENT_PASSWORD'
                """, AdminAccountRepository.uuidToBytes(userId)))
                .containsEntry("password_hash", "new-argon2-hash")
                .containsEntry("failed_attempts", 0)
                .containsEntry("locked_until", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_authorization WHERE user_id = ? AND consumed_at IS NULL",
                Integer.class, AdminAccountRepository.uuidToBytes(userId))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM payment_password_change_verification WHERE verification_id = ?",
                Boolean.class, AdminAccountRepository.uuidToBytes(verificationId))).isTrue();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                repository.changePaymentPassword(verificationId, userId, "device-1", "another-hash",
                        "another-idempotency-" + userId, changeHash, now.plusSeconds(2))))
                .isInstanceOf(AccountSecurityRejectedException.class)
                .extracting("code")
                .isEqualTo("VERIFICATION_TOKEN_USED");
    }

    private static void insertActiveUser(UUID userId, byte[] phoneHash) {
        jdbc.update("""
                INSERT INTO user_profile (
                  user_id, login_name, minipay_no, phone_hash, phone_masked, nickname,
                  status, onboarding_status, onboarding_completed_at, version,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, '138****0000', 'Account Security', 'ACTIVE',
                          'COMPLETED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, AdminAccountRepository.uuidToBytes(userId), "account-" + userId,
                "MP" + userId.toString().replace("-", "").substring(0, 20).toUpperCase(),
                phoneHash);
    }
}
