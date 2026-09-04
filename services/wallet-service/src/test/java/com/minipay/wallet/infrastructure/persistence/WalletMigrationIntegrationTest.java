package com.minipay.wallet.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.wallet.application.service.WalletProblemException;
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
class WalletMigrationIntegrationTest {
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
    void createsBankSettlementAccountBillsAndUsesOnlySeataFence() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_account
                WHERE account_role = 'BANK_SETTLEMENT'
                """, Long.class)).isOne();
        assertThat(tableExists("wallet_bill")).isTrue();
        assertThat(columnExists("wallet_bill", "source")).isTrue();
        assertThat(columnExists("account_freeze", "source")).isTrue();
        assertThat(columnExists("pending_credit", "source")).isTrue();
        assertThat(columnExists("wallet_bill", "counterparty_user_id")).isTrue();
        assertThat(columnExists("account_freeze", "counterparty_user_id")).isTrue();
        assertThat(columnExists("pending_credit", "counterparty_user_id")).isTrue();
        assertThat(tableExists("wallet_bill_management")).isTrue();
        assertThat(tableExists("wallet_user_tag")).isTrue();
        assertThat(tableExists("wallet_bill_tag_assignment")).isTrue();
        assertThat(indexExists("wallet_bill", "idx_wallet_bill_transfer_counterparty_time")).isTrue();
        assertThat(tableExists("tcc_fence_log")).isTrue();
        assertThat(tableExists("wallet_tcc_branch_fence")).isFalse();
    }

    @Test
    void enforcesTheLimitIdempotentlyAndReleasesItForTheOriginalPayment() {
        AnnualOutflowRepository limits = new AnnualOutflowRepository(jdbc, 100);
        UUID ownerId = UUID.randomUUID();

        limits.consume(ownerId, "PAYMENT", "payment-1", 60);
        limits.consume(ownerId, "PAYMENT", "payment-1", 60);
        limits.consume(ownerId, "PAYMENT", "payment-2", 40);

        assertThat(limits.current(ownerId).usedAmountCent()).isEqualTo(100);
        assertThatThrownBy(() -> limits.consume(ownerId, "PAYMENT", "payment-3", 1))
                .isInstanceOf(WalletProblemException.class)
                .hasMessage("ANNUAL_OUTFLOW_LIMIT_EXCEEDED");

        limits.releasePayment(ownerId, "payment-1", "refund-1", 60);
        limits.releasePayment(ownerId, "payment-1", "refund-1", 60);
        assertThat(limits.current(ownerId).usedAmountCent()).isEqualTo(40);
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) > 0
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Boolean.class, table);
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) > 0
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Boolean.class, table, column);
    }

    private boolean indexExists(String table, String index) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) > 0
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Boolean.class, table, index);
    }
}
