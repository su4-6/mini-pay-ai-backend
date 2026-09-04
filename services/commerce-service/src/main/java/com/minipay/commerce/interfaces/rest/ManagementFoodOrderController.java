package com.minipay.commerce.interfaces.rest;

import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.bytesToUuid;

import com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only food-order projection for the operations and system-admin portals. */
@Validated
@RestController
@RequestMapping({"/api/v1/ops/food-orders", "/api/v1/admin/orders/food-orders"})
public class ManagementFoodOrderController {
    private final JdbcTemplate jdbc;

    public ManagementFoodOrderController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public FoodOrderPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String fulfillmentStatus,
            @RequestParam(required = false) String refundStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Query query = query(orderNo, paymentStatus, fulfillmentStatus, refundStatus, from, to);
        List<Object> pageArgs = new ArrayList<>(query.args());
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<FoodOrderView> items = jdbc.query("""
                SELECT order_ref_id,user_id,provider,external_order_no,payment_order_id,
                       amount_cent,currency,payment_status,fulfillment_status,refund_status,
                       expires_at,created_at,updated_at
                  FROM food_external_order_ref
                """ + query.where() + " ORDER BY created_at DESC,order_ref_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageArgs.toArray());
        Long total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM food_external_order_ref" + query.where(),
                Long.class, query.args().toArray());
        return new FoodOrderPage(items, page, size, total == null ? 0 : total);
    }

    @GetMapping("/{orderRefId}")
    @Transactional(readOnly = true)
    public FoodOrderView detail(@PathVariable UUID orderRefId) {
        return jdbc.query("""
                SELECT order_ref_id,user_id,provider,external_order_no,payment_order_id,
                       amount_cent,currency,payment_status,fulfillment_status,refund_status,
                       expires_at,created_at,updated_at
                  FROM food_external_order_ref WHERE order_ref_id=?
                """, (rs, row) -> map(rs), JdbcCommerceRepository.uuidToBytes(orderRefId)).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FOOD_ORDER_NOT_FOUND"));
    }

    private static Query query(String orderNo, String paymentStatus, String fulfillmentStatus,
            String refundStatus, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder(" WHERE provider='YSHOP'");
        List<Object> args = new ArrayList<>();
        append(where, args, "external_order_no", orderNo);
        append(where, args, "payment_status", paymentStatus);
        append(where, args, "fulfillment_status", fulfillmentStatus);
        append(where, args, "refund_status", refundStatus);
        if (from != null) {
            where.append(" AND created_at>=?");
            args.add(Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (to != null) {
            where.append(" AND created_at<?");
            args.add(Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        return new Query(where.toString(), args);
    }

    private static void append(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append("=?");
            args.add(value.trim());
        }
    }

    private static FoodOrderView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        byte[] paymentId = rs.getBytes("payment_order_id");
        return new FoodOrderView(
                bytesToUuid(rs.getBytes("order_ref_id")), bytesToUuid(rs.getBytes("user_id")),
                rs.getString("provider"), rs.getString("external_order_no"),
                paymentId == null ? null : bytesToUuid(paymentId), rs.getLong("amount_cent"),
                rs.getString("currency"), rs.getString("payment_status"),
                rs.getString("fulfillment_status"), rs.getString("refund_status"),
                instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    record Query(String where, List<Object> args) { }
    public record FoodOrderPage(List<FoodOrderView> items, int page, int size, long total) { }
    public record FoodOrderView(UUID orderRefId, UUID userId, String provider, String externalOrderNo,
            UUID paymentOrderId, long amountCent, String currency, String paymentStatus,
            String fulfillmentStatus, String refundStatus, Instant expiresAt, Instant createdAt,
            Instant updatedAt) { }
}
