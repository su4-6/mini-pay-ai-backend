package com.minipay.wallet.infrastructure.persistence;

import com.minipay.wallet.application.service.UuidV7;
import com.minipay.wallet.application.service.WalletProblemException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnnualOutflowRepository {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final long configuredLimitCent;

    public AnnualOutflowRepository(
            JdbcTemplate jdbc,
            @Value("${minipay.wallet.annual-outflow-limit-cent:20000000}")
            long configuredLimitCent) {
        if (configuredLimitCent < 0) throw new IllegalArgumentException("Annual limit cannot be negative");
        this.jdbc = jdbc;
        this.configuredLimitCent = configuredLimitCent;
    }

    public int currentYear() {
        return Instant.now().atZone(CHINA).getYear();
    }

    public LimitRow current(UUID ownerId) {
        int year = currentYear();
        LimitRow row = find(ownerId, year, false);
        return row == null ? new LimitRow(year, configuredLimitCent, 0, 0) : row;
    }

    public void consume(UUID ownerId, String businessType, String businessNo, long amountCent) {
        int year = currentYear();
        LimitRow row = lockOrCreate(ownerId, year);
        if (adjustmentExists(ownerId, businessType, businessNo, "CONSUME")) return;
        requireCapacity(row, amountCent);
        insertAdjustment(ownerId, year, businessType, businessNo, "CONSUME", amountCent);
        update(ownerId, year, amountCent, 0);
    }

    public int reserve(UUID ownerId, String businessNo, long amountCent) {
        int year = currentYear();
        LimitRow row = lockOrCreate(ownerId, year);
        requireCapacity(row, amountCent);
        update(ownerId, year, 0, amountCent);
        return year;
    }

    public void confirmReservation(
            UUID ownerId, String businessNo, int year, long amountCent) {
        lockOrCreate(ownerId, year);
        if (adjustmentExists(ownerId, "TRANSFER", businessNo, "CONSUME")) return;
        insertAdjustment(ownerId, year, "TRANSFER", businessNo, "CONSUME", amountCent);
        int changed = jdbc.update("""
                UPDATE annual_outflow_limit
                SET reserved_amount_cent = reserved_amount_cent - ?,
                    used_amount_cent = used_amount_cent + ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE owner_id = ? AND limit_year = ? AND reserved_amount_cent >= ?
                """, amountCent, amountCent, bytes(ownerId), year, amountCent);
        if (changed != 1) throw new IllegalStateException("Annual limit reservation is missing");
    }

    public void cancelReservation(
            UUID ownerId, String businessNo, int year, long amountCent) {
        lockOrCreate(ownerId, year);
        if (adjustmentExists(ownerId, "TRANSFER", businessNo, "RELEASE")) return;
        insertAdjustment(ownerId, year, "TRANSFER", businessNo, "RELEASE", amountCent);
        int changed = jdbc.update("""
                UPDATE annual_outflow_limit
                SET reserved_amount_cent = reserved_amount_cent - ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE owner_id = ? AND limit_year = ? AND reserved_amount_cent >= ?
                """, amountCent, bytes(ownerId), year, amountCent);
        if (changed != 1) throw new IllegalStateException("Annual limit reservation is missing");
    }

    public void releasePayment(
            UUID ownerId, String paymentBusinessNo, String refundBusinessNo, long amountCent) {
        Integer year = jdbc.query("""
                SELECT limit_year, amount_cent FROM annual_outflow_adjustment
                WHERE owner_id = ? AND business_type = 'PAYMENT' AND business_no = ?
                  AND adjustment_type = 'CONSUME'
                """, rs -> rs.next() && rs.getLong("amount_cent") == amountCent
                        ? rs.getInt("limit_year") : null, bytes(ownerId), paymentBusinessNo);
        if (year == null) return;
        lockOrCreate(ownerId, year);
        if (adjustmentExists(ownerId, "REFUND", refundBusinessNo, "RELEASE")) return;
        insertAdjustment(ownerId, year, "REFUND", refundBusinessNo, "RELEASE", amountCent);
        jdbc.update("""
                UPDATE annual_outflow_limit
                SET used_amount_cent = GREATEST(used_amount_cent - ?, 0), version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE owner_id = ? AND limit_year = ?
                """, amountCent, bytes(ownerId), year);
    }

    private LimitRow lockOrCreate(UUID ownerId, int year) {
        jdbc.update("""
                INSERT IGNORE INTO annual_outflow_limit (
                  owner_id, limit_year, limit_amount_cent, used_amount_cent,
                  reserved_amount_cent, version, created_at, updated_at
                ) VALUES (?, ?, ?, 0, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(ownerId), year, configuredLimitCent);
        return find(ownerId, year, true);
    }

    private LimitRow find(UUID ownerId, int year, boolean lock) {
        return jdbc.query("""
                SELECT limit_year, limit_amount_cent, used_amount_cent, reserved_amount_cent
                FROM annual_outflow_limit WHERE owner_id = ? AND limit_year = ?
                """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
                ? new LimitRow(rs.getInt("limit_year"), rs.getLong("limit_amount_cent"),
                rs.getLong("used_amount_cent"), rs.getLong("reserved_amount_cent")) : null,
                bytes(ownerId), year);
    }

    private void requireCapacity(LimitRow row, long amountCent) {
        if (row.usedAmountCent() + row.reservedAmountCent() + amountCent > row.limitAmountCent()) {
            throw new WalletProblemException(
                    "ANNUAL_OUTFLOW_LIMIT_EXCEEDED", HttpStatus.CONFLICT);
        }
    }

    private void update(UUID ownerId, int year, long usedDelta, long reservedDelta) {
        jdbc.update("""
                UPDATE annual_outflow_limit SET used_amount_cent = used_amount_cent + ?,
                    reserved_amount_cent = reserved_amount_cent + ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6) WHERE owner_id = ? AND limit_year = ?
                """, usedDelta, reservedDelta, bytes(ownerId), year);
    }

    private boolean adjustmentExists(UUID ownerId, String type, String no, String adjustment) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM annual_outflow_adjustment
                  WHERE owner_id = ? AND business_type = ? AND business_no = ?
                    AND adjustment_type = ?)
                """, Boolean.class, bytes(ownerId), type, no, adjustment);
        return Boolean.TRUE.equals(exists);
    }

    private void insertAdjustment(
            UUID ownerId, int year, String type, String no, String adjustment, long amountCent) {
        jdbc.update("""
                INSERT INTO annual_outflow_adjustment (
                  adjustment_id, owner_id, limit_year, business_type, business_no,
                  adjustment_type, amount_cent, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bytes(UuidV7.generate()), bytes(ownerId), year, type, no, adjustment, amountCent);
    }

    private static byte[] bytes(UUID id) {
        return WalletRepository.uuidToBytes(id);
    }

    public record LimitRow(
            int year, long limitAmountCent, long usedAmountCent, long reservedAmountCent) {
    }
}
