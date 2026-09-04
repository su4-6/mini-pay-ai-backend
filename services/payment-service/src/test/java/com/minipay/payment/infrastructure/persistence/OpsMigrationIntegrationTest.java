package com.minipay.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OpsMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void migratesOpsTablesAndAddsNullablePaymentOwnership() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("23")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO merchant_apply (
                       user_id, merchant_type, shop_name, normalized_shop_name,
                       contact_name, contact_mobile, apply_status, apply_time,
                       version, created_at, updated_at
                     ) VALUES (
                       UNHEX(REPLACE('018f13d8-1111-7000-8000-000000000001', '-', '')),
                       'PERSONAL', ?, LOWER(?), '张*', '13800000000', ?,
                       CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                     )
                     """)) {
            statement.setString(1, "Old shop");
            statement.setString(2, "Old shop");
            statement.setString(3, "REJECTED");
            statement.executeUpdate();
            statement.setString(1, "Current shop");
            statement.setString(2, "Current shop");
            statement.setString(3, "PENDING");
            statement.executeUpdate();
        }

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertThat(count(connection, "merchant")).isEqualTo(1);
            assertThat(count(connection, "platform_daily_metric")).isEqualTo(1);
            assertThat(columnNullable(connection, "payment_order", "merchant_id"))
                    .isEqualTo("YES");
            assertThat(columnNullable(connection, "merchant", "short_name")).isEqualTo("NO");
            assertThat(columnNullable(connection, "merchant", "contact_name")).isEqualTo("YES");
            assertThat(columnCount(connection, "merchant", "contact_mobile")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "contact_email")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "remark")).isEqualTo(1);
            assertThat(columnNullable(connection, "merchant_application", "version"))
                    .isEqualTo("NO");
            assertThat(indexCount(connection, "merchant_application",
                    "uk_merchant_application_merchant_name")).isEqualTo(2);
            assertThat(count(connection, "merchant_apply")).isEqualTo(1);
            assertThat(count(connection, "merchant_onboarding_guard")).isEqualTo(1);
            assertThat(guardUsesLatestApplication(connection)).isTrue();
            assertThat(columnCount(connection, "merchant", "merchant_type")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "mcc_code")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "address")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "shop_images")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "freeze_reason")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant", "service_provider_no")).isZero();
            assertThat(columnCount(connection, "merchant_apply", "latitude")).isEqualTo(1);
            assertThat(columnCount(connection, "merchant_apply", "longitude")).isEqualTo(1);
            assertThat(indexCount(connection, "merchant_apply",
                    "idx_apply_user_status")).isEqualTo(2);
            assertThat(indexCount(connection, "merchant_apply",
                    "idx_apply_status_time")).isEqualTo(2);
        }
    }

    private static long count(java.sql.Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(1) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static boolean guardUsesLatestApplication(java.sql.Connection connection)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT g.apply_id = MAX(a.id)
                  FROM merchant_onboarding_guard g
                  JOIN merchant_apply a ON a.user_id = g.user_id
                 GROUP BY g.user_id, g.apply_id
                """); var result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static long columnCount(
            java.sql.Connection connection, String table, String column) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(1) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String columnNullable(
            java.sql.Connection connection, String table, String column) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static long indexCount(
            java.sql.Connection connection, String table, String index) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(1) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
