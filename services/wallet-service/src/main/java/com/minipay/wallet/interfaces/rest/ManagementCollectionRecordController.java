package com.minipay.wallet.interfaces.rest;

import static com.minipay.wallet.infrastructure.persistence.WalletRepository.bytesToUuid;
import static com.minipay.wallet.infrastructure.persistence.WalletRepository.uuidToBytes;

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

/** Global read-only collection records for operations and system administration. */
@Validated
@RestController
@RequestMapping({"/api/v1/management/collection-records", "/api/v1/admin/orders/collection-records"})
public class ManagementCollectionRecordController {
    private final JdbcTemplate jdbc;

    public ManagementCollectionRecordController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public CollectionPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) String businessNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Query query = query(ownerId, businessNo, status, type, from, to);
        List<Object> pageArgs = new ArrayList<>(query.args());
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<CollectionView> items = jdbc.query("""
                SELECT bill_id,owner_id,business_type,business_no,source,direction,amount_cent,
                       counterparty_display,remark,status,balance_after_cent,failure_code,occurred_at,updated_at
                  FROM wallet_bill
                """ + query.where() + " ORDER BY occurred_at DESC,bill_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM wallet_bill" + query.where(),
                Long.class, query.args().toArray());
        Summary summary = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction='INCOME' AND status='SUCCEEDED' THEN 1 ELSE 0 END),0) collection_count,
                       COALESCE(SUM(CASE WHEN direction='INCOME' AND status='SUCCEEDED' THEN amount_cent ELSE 0 END),0) collection_amount,
                       COALESCE(SUM(CASE WHEN source='MERCHANT_REFUND' AND status='SUCCEEDED' THEN 1 ELSE 0 END),0) refund_count,
                       COALESCE(SUM(CASE WHEN source='MERCHANT_REFUND' AND status='SUCCEEDED' THEN amount_cent ELSE 0 END),0) refund_amount
                  FROM wallet_bill
                """ + query.where(), (rs, row) -> {
            long collections = rs.getLong("collection_amount");
            long refunds = rs.getLong("refund_amount");
            return new Summary(rs.getLong("collection_count"), collections,
                    rs.getLong("refund_count"), refunds, collections - refunds);
        }, query.args().toArray());
        return new CollectionPage(items, page, size, total == null ? 0 : total, summary);
    }

    @GetMapping("/{billId}")
    @Transactional(readOnly = true)
    public CollectionView detail(@PathVariable UUID billId) {
        Query query = query(null, null, null, null, null, null);
        return jdbc.query("""
                SELECT bill_id,owner_id,business_type,business_no,source,direction,amount_cent,
                       counterparty_display,remark,status,balance_after_cent,failure_code,occurred_at,updated_at
                  FROM wallet_bill
                """ + query.where() + " AND bill_id=?", (rs, row) -> map(rs), uuidToBytes(billId))
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "COLLECTION_RECORD_NOT_FOUND"));
    }

    private static Query query(UUID ownerId, String businessNo, String status, String type,
            LocalDate from, LocalDate to) {
        String sourcePredicate = switch (type == null ? "ALL" : type.trim().toUpperCase()) {
            case "PERSONAL" -> "source='PERSONAL_COLLECTION_CODE' AND direction='INCOME'";
            case "MERCHANT" -> "source IN ('MERCHANT_PAYMENT','MERCHANT_REFUND')";
            case "COLLECTION" -> "source IN ('PERSONAL_COLLECTION_CODE','MERCHANT_PAYMENT') AND direction='INCOME'";
            case "REFUND" -> "source='MERCHANT_REFUND'";
            default -> "source IN ('PERSONAL_COLLECTION_CODE','MERCHANT_PAYMENT','MERCHANT_REFUND')";
        };
        StringBuilder where = new StringBuilder(" WHERE ").append(sourcePredicate);
        List<Object> args = new ArrayList<>();
        if (ownerId != null) {
            where.append(" AND owner_id=?");
            args.add(uuidToBytes(ownerId));
        }
        append(where, args, "business_no", businessNo);
        append(where, args, "status", status);
        if (from != null) {
            where.append(" AND occurred_at>=?");
            args.add(Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (to != null) {
            where.append(" AND occurred_at<?");
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

    private static CollectionView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CollectionView(bytesToUuid(rs.getBytes("bill_id")), bytesToUuid(rs.getBytes("owner_id")),
                rs.getString("business_type"), rs.getString("business_no"), rs.getString("source"),
                rs.getString("direction"), rs.getLong("amount_cent"), rs.getString("counterparty_display"),
                rs.getString("remark"), rs.getString("status"),
                rs.getObject("balance_after_cent", Long.class), rs.getString("failure_code"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    record Query(String where, List<Object> args) { }
    public record CollectionPage(List<CollectionView> items, int page, int size, long total, Summary summary) { }
    public record Summary(long collectionCount, long collectionAmountCent, long refundCount,
            long refundAmountCent, long netAmountCent) { }
    public record CollectionView(UUID billId, UUID ownerId, String businessType, String businessNo,
            String source, String direction, long amountCent, String counterpartyDisplay, String remark,
            String status, Long balanceAfterCent, String failureCode, Instant occurredAt, Instant updatedAt) { }
}
