package com.minipay.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PaymentMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsBankFundingPaymentMethodAndRecoverableTransferTables() {
        assertThat(countColumns("bank_card", "provider_token")).isOne();
        assertThat(countColumns("recharge_order", "bank_card_id")).isOne();
        assertThat(countColumns("recharge_order", "authorization_id")).isOne();
        assertThat(countColumns("recharge_order", "confirmation_expires_at")).isOne();
        assertThat(countColumns("withdrawal_order", "authorization_id")).isOne();
        assertThat(countColumns("payment_order", "authorization_id")).isOne();
        assertThat(countColumns("transfer_order", "tcc_state")).isZero();
        assertThat(countColumns("transfer_order", "xid")).isOne();
        assertThat(countColumns("transfer_order", "execution_lease_until")).isOne();
        assertThat(countColumns("transfer_order", "recovery_error_code")).isOne();
        assertThat(countColumns("outbox_event", "lease_owner")).isOne();
        assertThat(countColumns("outbox_event", "last_error")).isOne();
        assertThat(countColumns("sandbox_bank_account", "available_amount_cent")).isOne();
        assertThat(countColumns("sandbox_bank_transaction", "request_no")).isOne();
        assertThat(countColumns("sandbox_bank_transaction", "failure_code")).isOne();
        // V5 keeps account passwords in Identity. Payment owns only merchant
        // configuration, operational applications and collection capabilities.
        assertThat(countColumns("merchant", "default_application_id")).isOne();
        assertThat(countColumns("merchant", "source")).isOne();
        assertThat(countColumns("merchant_application", "app_secret_ciphertext")).isOne();
        assertThat(countColumns("merchant_application", "available_channels")).isOne();
        assertThat(countColumns("merchant_application", "version")).isOne();
        assertThat(countColumns("merchant_scan_resolution", "version")).isOne();
        assertThat(countColumns("merchant_notification_attempt", "attempt_id")).isOne();
        assertThat(countColumns("merchant_operation_audit", "audit_id")).isOne();
        assertThat(countColumns("payment_order", "merchant_id")).isOne();
        assertThat(countColumns("payment_order", "application_id")).isOne();
        assertThat(countColumns("payment_order", "resolution_id")).isOne();
        assertThat(countColumns("food_order_payment_reference", "food_order_id")).isOne();
        assertThat(countColumns("food_order_payment_reference", "created_event_id")).isOne();
        assertThat(countColumns("refund_order", "failure_code")).isOne();
    }

    @Test
    void rechargeStatusColumnFitsEverySupportedStatus() {
        Integer maximumLength = jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'recharge_order'
                  AND column_name = 'status'
                """, Integer.class);

        assertThat(maximumLength).isGreaterThanOrEqualTo("PENDING_CONFIRMATION".length());
    }

    private long countColumns(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Long.class, table, column);
    }
}
