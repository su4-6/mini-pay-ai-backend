package com.minipay.wallet.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
class CollectionRecordIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static WalletRepository repository;
    static JdbcTemplate jdbc;
    static UUID ownerId;
    static UUID accountId;

    @BeforeAll
    static void prepare() {
        var dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new WalletRepository(jdbc);
        ownerId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        repository.insertConsumerAccount(accountId, "C-test-collection", ownerId);
    }

    @Test
    void filtersAndAggregatesPersonalMerchantAndRefundRecords() {
        UUID personal = insert("PERSONAL_COLLECTION_CODE", "INCOME", 1_000, "SUCCEEDED", "P-1");
        insert("MERCHANT_PAYMENT", "INCOME", 2_000, "SUCCEEDED", "M-1");
        insert("MERCHANT_REFUND", "EXPENSE", 500, "SUCCEEDED", "R-1");
        insert("MERCHANT_PAYMENT", "INCOME", 9_999, "FAILED", "M-failed");
        jdbc.update("""
                INSERT INTO wallet_bill_management
                  (bill_id, owner_id, category_code, include_in_statistics, created_at, updated_at)
                VALUES (?, ?, 'OTHER', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, WalletRepository.uuidToBytes(personal), WalletRepository.uuidToBytes(ownerId));

        Instant from = Instant.parse("2020-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-01-01T00:00:00Z");
        var all = repository.queryCollectionRecords(ownerId, "ALL", "TODAY", from, to, 1, 2);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).hasSize(2);
        assertThat(all.summary().collectionCount()).isEqualTo(2);
        assertThat(all.summary().collectionAmountCent()).isEqualTo(3_000);
        assertThat(all.summary().refundCount()).isOne();
        assertThat(all.summary().refundAmountCent()).isEqualTo(500);
        assertThat(all.summary().netAmountCent()).isEqualTo(2_500);

        var merchant = repository.queryCollectionRecords(ownerId, "MERCHANT", "MONTH", from, to, 1, 20);
        assertThat(merchant.total()).isEqualTo(2);
        assertThat(merchant.summary().collectionAmountCent()).isEqualTo(2_000);
        assertThat(merchant.summary().refundAmountCent()).isEqualTo(500);
    }

    private static UUID insert(String source, String direction, long amount, String status, String no) {
        UUID billId = UUID.randomUUID();
        repository.insertBill(billId, ownerId, accountId,
                source.equals("PERSONAL_COLLECTION_CODE") ? "TRANSFER" : source,
                no, direction, amount, "counterparty", "remark", status,
                amount, status.equals("FAILED") ? "FAILED" : null, source, UUID.randomUUID());
        return billId;
    }
}
