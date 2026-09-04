package com.minipay.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceCheckoutService;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.domain.model.CartSnapshot;
import com.minipay.commerce.domain.model.CheckoutQuote;
import com.minipay.commerce.domain.model.FoodOrder;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import com.minipay.commerce.domain.model.RefundStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CommercePersistenceIntegrationTest {
    private static final UUID MERCHANT_ID =
            UUID.fromString("019fdf36-0001-7000-8000-000000000001");
    private static final UUID SKU_ID =
            UUID.fromString("019fdf36-0004-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fdf36-1000-7000-8000-000000000001");
    private static final UUID ADDRESS_ID = UUID.fromString("019fdf36-1001-7000-8000-000000000001");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static JdbcTemplate jdbc;
    static JdbcCommerceRepository repository;
    static TransactionTemplate transactions;
    static CommerceCheckoutService checkout;
    static CommerceOrderService orders;

    @BeforeAll
    static void migrateAndSeedAddress() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcCommerceRepository(jdbc, new ObjectMapper());
        checkout = new CommerceCheckoutService(repository, Duration.ofMinutes(10), Duration.ofMinutes(15));
        orders = new CommerceOrderService(repository);

        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO delivery_address (
                  address_id, user_id, label, masked_summary, recipient_ciphertext,
                  mobile_ciphertext, address_ciphertext, encryption_key_version,
                  zone_code, is_default, status, version, created_at, updated_at
                ) VALUES (?, ?, 'HOME', '家 · 浦东新区***路', ?, ?, ?, 1,
                          'CN-SH-PD', TRUE, 'ACTIVE', 0, ?, ?)
                """,
                JdbcCommerceRepository.uuidToBytes(ADDRESS_ID),
                JdbcCommerceRepository.uuidToBytes(USER_ID),
                new byte[] {1}, new byte[] {2}, new byte[] {3},
                Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void migratesCatalogAndCompletesOrderRefundSagaIdempotently() {
        assertThat(repository.searchMerchants("CN-SH-PD", "CHINESE", 500L, 40, 10))
                .extracting("id").containsExactly(MERCHANT_ID);
        assertThat(repository.menu(MERCHANT_ID)).hasSize(1);

        CartSnapshot cart = inTransaction(() -> repository.updateCart(
                USER_ID, MERCHANT_ID, SKU_ID, 1, Set.of(), null, Instant.now()));
        CheckoutQuote quote = inTransaction(() -> checkout.prepareQuote(
                USER_ID, MERCHANT_ID, ADDRESS_ID, cart.version(), "quote-key-0001"));
        FoodOrder created = inTransaction(() -> checkout.createOrder(
                USER_ID, quote.id(), "order-key-0001"));

        assertThat(created.status()).isEqualTo(FoodOrderStatus.PENDING_PAYMENT);
        assertInventory(99, 1);
        assertThat(countOutbox("commerce.food-order.created")).isOne();

        UUID paymentOrderId = UUID.randomUUID();
        UUID paidEventId = UUID.randomUUID();
        FoodOrder paid = inTransaction(() -> orders.paymentSucceeded(
                paidEventId, created.id(), paymentOrderId, created.payableAmountCent()));
        FoodOrder duplicate = inTransaction(() -> orders.paymentSucceeded(
                paidEventId, created.id(), paymentOrderId, created.payableAmountCent()));
        assertThat(paid.status()).isEqualTo(FoodOrderStatus.PAID);
        assertThat(duplicate.version()).isEqualTo(paid.version());
        assertInventory(99, 0);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM delivery_task WHERE order_id = ?",
                String.class,
                JdbcCommerceRepository.uuidToBytes(created.id()))).isEqualTo("CREATED");

        FoodOrder cancelling = inTransaction(() -> orders.cancel(USER_ID, created.id()));
        assertThat(cancelling.status()).isEqualTo(FoodOrderStatus.CANCELLATION_PENDING);
        assertThat(cancelling.refundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(countOutbox("commerce.refund.requested")).isOne();

        inTransaction(() -> orders.refundProcessing(UUID.randomUUID(), created.id()));
        FoodOrder cancelled = inTransaction(() -> orders.refundSucceeded(
                UUID.randomUUID(), created.id(), created.payableAmountCent()));
        assertThat(cancelled.status()).isEqualTo(FoodOrderStatus.CANCELLED);
        assertThat(cancelled.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message", Long.class)).isEqualTo(3L);
    }

    private static void assertInventory(int available, int reserved) {
        assertThat(jdbc.queryForMap("""
                SELECT available_quantity, reserved_quantity
                FROM sku_inventory
                WHERE sku_id = ?
                """, JdbcCommerceRepository.uuidToBytes(SKU_ID)))
                .containsEntry("available_quantity", available)
                .containsEntry("reserved_quantity", reserved);
    }

    private static long countOutbox(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?",
                Long.class,
                eventType);
    }

    private static <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        return transactions.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
