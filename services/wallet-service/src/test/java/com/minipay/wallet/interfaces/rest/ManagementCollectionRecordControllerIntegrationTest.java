package com.minipay.wallet.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.wallet.infrastructure.persistence.WalletRepository;
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
class ManagementCollectionRecordControllerIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static ManagementCollectionRecordController controller;
    static WalletRepository repository;
    static UUID ownerId;
    static UUID accountId;
    static UUID collectionBill;

    @BeforeAll
    static void prepare() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        controller = new ManagementCollectionRecordController(jdbc);
        repository = new WalletRepository(jdbc);
        ownerId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        repository.insertConsumerAccount(accountId, "C-management-test", ownerId);
        collectionBill = insert("MERCHANT_PAYMENT", "INCOME", 2000, "SUCCEEDED", "PAY-1");
        insert("MERCHANT_REFUND", "EXPENSE", 500, "SUCCEEDED", "REF-1");
        insert("MERCHANT_PAYMENT", "INCOME", 9000, "FAILED", "PAY-FAILED");
    }

    @Test
    void usesZeroBasedPagingAndSuccessfulAmountsForSummary() {
        var page = controller.list(0, 2, ownerId, null, null, null, null, null);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(2);
        assertThat(page.summary().collectionAmountCent()).isEqualTo(2000);
        assertThat(page.summary().refundAmountCent()).isEqualTo(500);
        assertThat(page.summary().netAmountCent()).isEqualTo(1500);
        assertThat(controller.detail(collectionBill).billId()).isEqualTo(collectionBill);
    }

    @Test
    void filtersCollectionAndRefundTypes() {
        assertThat(controller.list(0, 20, ownerId, null, null, "COLLECTION", null, null).total()).isEqualTo(2);
        assertThat(controller.list(0, 20, ownerId, null, null, "REFUND", null, null).total()).isOne();
    }

    private static UUID insert(String source, String direction, long amount, String status, String no) {
        UUID billId = UUID.randomUUID();
        repository.insertBill(billId, ownerId, accountId, source, no, direction, amount,
                "counterparty", "remark", status, amount, status.equals("FAILED") ? "FAILED" : null,
                source, UUID.randomUUID());
        return billId;
    }
}
