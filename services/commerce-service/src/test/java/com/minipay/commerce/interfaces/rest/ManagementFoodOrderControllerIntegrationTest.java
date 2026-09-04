package com.minipay.commerce.interfaces.rest;

import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;

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
class ManagementFoodOrderControllerIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static ManagementFoodOrderController controller;
    static JdbcTemplate jdbc;
    static UUID yshopOrder;

    @BeforeAll
    static void prepare() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        controller = new ManagementFoodOrderController(jdbc);
        yshopOrder = insert("YSHOP", "YS-100", "PAID", "DELIVERING", "NONE");
        insert("OTHER", "OTHER-100", "PAID", "DELIVERING", "NONE");
    }

    @Test
    void pagesFiltersAndLoadsYshopOrderDetail() {
        var page = controller.list(0, 1, "YS-100", "PAID", null, null, null, null);
        assertThat(page.total()).isOne();
        assertThat(page.items()).extracting(ManagementFoodOrderController.FoodOrderView::externalOrderNo)
                .containsExactly("YS-100");
        assertThat(controller.detail(yshopOrder).orderRefId()).isEqualTo(yshopOrder);
    }

    private static UUID insert(String provider, String externalNo, String payment, String fulfillment, String refund) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO food_external_order_ref
                  (order_ref_id,user_id,provider,external_order_no,quote_ref_id,idempotency_key,
                   amount_cent,currency,payment_status,fulfillment_status,refund_status,
                   expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,1888,'CNY',?,?,?,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, uuidToBytes(id), uuidToBytes(UUID.randomUUID()), provider, externalNo,
                uuidToBytes(UUID.randomUUID()), "idem-" + externalNo, payment, fulfillment, refund);
        return id;
    }
}
