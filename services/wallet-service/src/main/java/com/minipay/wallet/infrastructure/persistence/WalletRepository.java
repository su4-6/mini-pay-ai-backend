package com.minipay.wallet.infrastructure.persistence;

import com.minipay.wallet.domain.model.BillPage;
import com.minipay.wallet.domain.model.BillQuery;
import com.minipay.wallet.domain.model.BillTag;
import com.minipay.wallet.domain.model.CollectionRecordPage;
import com.minipay.wallet.domain.model.WalletBill;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {
    public static final UUID SANDBOX_ISSUANCE_ACCOUNT =
            UUID.fromString("00000000-0000-7000-8000-000000000001");
    public static final UUID TRANSFER_CLEARING_ACCOUNT =
            UUID.fromString("00000000-0000-7000-8000-000000000002");
    public static final UUID BANK_SETTLEMENT_ACCOUNT =
            UUID.fromString("00000000-0000-7000-8000-000000000003");

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AccountRow> findConsumerAccount(UUID ownerId) {
        return queryConsumerAccount(ownerId, false);
    }

    public Optional<AccountRow> lockConsumerAccount(UUID ownerId) {
        return queryConsumerAccount(ownerId, true);
    }

    public Optional<AccountRow> lockAccountById(UUID accountId) {
        return jdbcTemplate.query("""
                        SELECT account_id, owner_id, available_amount_cent, frozen_amount_cent,
                               currency, status
                        FROM wallet_account
                        WHERE account_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new AccountRow(
                        bytesToUuid(resultSet.getBytes("account_id")),
                        bytesToUuid(resultSet.getBytes("owner_id")),
                        resultSet.getLong("available_amount_cent"),
                        resultSet.getLong("frozen_amount_cent"),
                        resultSet.getString("currency"),
                        resultSet.getString("status")))
                        : Optional.empty(),
                uuidToBytes(accountId));
    }

    public Optional<OpsAccountRow> findOpsAccount(UUID accountId) {
        return jdbcTemplate.query("""
                        SELECT account_id, account_no, owner_type, owner_id, currency,
                               account_role, available_amount_cent, frozen_amount_cent,
                               status, updated_at
                        FROM wallet_account
                        WHERE account_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new OpsAccountRow(
                        bytesToUuid(resultSet.getBytes("account_id")),
                        resultSet.getString("account_no"),
                        resultSet.getString("owner_type"),
                        bytesToUuid(resultSet.getBytes("owner_id")),
                        resultSet.getString("currency"),
                        resultSet.getString("account_role"),
                        resultSet.getLong("available_amount_cent"),
                        resultSet.getLong("frozen_amount_cent"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                uuidToBytes(accountId));
    }

    public List<OpsAccountRow> listOpsAccounts(int page, int size, UUID ownerId, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE owner_type='CONSUMER'");
        appendFilter(where, args, "owner_id = ?", uuidToBytes(ownerId));
        appendFilter(where, args, "status = ?", status);
        args.add(size); args.add((page - 1) * size);
        return jdbcTemplate.query("""
                SELECT account_id,account_no,owner_type,owner_id,currency,account_role,
                       available_amount_cent,frozen_amount_cent,status,updated_at
                FROM wallet_account
                """ + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs,n)->new OpsAccountRow(bytesToUuid(rs.getBytes("account_id")),rs.getString("account_no"),
                        rs.getString("owner_type"),bytesToUuid(rs.getBytes("owner_id")),rs.getString("currency"),
                        rs.getString("account_role"),rs.getLong("available_amount_cent"),rs.getLong("frozen_amount_cent"),
                        rs.getString("status"),rs.getTimestamp("updated_at").toInstant()),args.toArray());
    }

    public long countOpsAccounts(UUID ownerId, String status) {
        List<Object> args=new ArrayList<>(); StringBuilder where=new StringBuilder(" WHERE owner_type='CONSUMER'");
        appendFilter(where,args,"owner_id = ?",uuidToBytes(ownerId)); appendFilter(where,args,"status = ?",status);
        Long total=jdbcTemplate.queryForObject("SELECT COUNT(1) FROM wallet_account"+where,Long.class,args.toArray());
        return total==null?0:total;
    }

    public Optional<LedgerTransactionRow> findLedgerTransaction(UUID transactionId) {
        return jdbcTemplate.query("""
                        SELECT transaction_id, transaction_no, business_type, business_no,
                               debit_total_amount_cent, credit_total_amount_cent, occurred_at
                        FROM ledger_transaction
                        WHERE transaction_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapLedgerTransaction(resultSet))
                        : Optional.empty(),
                uuidToBytes(transactionId));
    }

    public List<LedgerTransactionRow> listLedgerTransactions(int page, int size) {
        return jdbcTemplate.query("""
                        SELECT transaction_id, transaction_no, business_type, business_no,
                               debit_total_amount_cent, credit_total_amount_cent, occurred_at
                        FROM ledger_transaction
                        ORDER BY occurred_at DESC, transaction_id DESC
                        LIMIT ? OFFSET ?
                        """,
                (resultSet, rowNumber) -> mapLedgerTransaction(resultSet),
                size,
                (page - 1) * size);
    }

    public long countLedgerTransactions() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction", Long.class);
    }

    public List<LedgerEntryRow> listLedgerEntries(UUID transactionId) {
        return jdbcTemplate.query("""
                        SELECT entry_id, transaction_id, account_id, direction,
                               amount_cent, created_at
                        FROM ledger_entry
                        WHERE transaction_id = ?
                        ORDER BY direction, entry_id
                        """,
                (resultSet, rowNumber) -> new LedgerEntryRow(
                        bytesToUuid(resultSet.getBytes("entry_id")),
                        bytesToUuid(resultSet.getBytes("transaction_id")),
                        bytesToUuid(resultSet.getBytes("account_id")),
                        resultSet.getString("direction"),
                        resultSet.getLong("amount_cent"),
                        resultSet.getTimestamp("created_at").toInstant()),
                uuidToBytes(transactionId));
    }

    public void insertConsumerAccount(UUID accountId, String accountNo, UUID ownerId) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO wallet_account (
                  account_id, account_no, owner_type, owner_id, currency, account_role,
                  available_amount_cent, frozen_amount_cent, status, version, created_at, updated_at
                ) VALUES (?, ?, 'CONSUMER', ?, 'CNY', 'CONSUMER_WALLET',
                          0, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uuidToBytes(accountId), accountNo, uuidToBytes(ownerId));
    }

    public Optional<PostingRow> findPosting(String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT request_hash, business_type, business_no, result_bill_id
                        FROM wallet_posting_idempotency
                        WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new PostingRow(
                        resultSet.getBytes("request_hash"),
                        resultSet.getString("business_type"),
                        resultSet.getString("business_no"),
                        bytesToUuid(resultSet.getBytes("result_bill_id"))))
                        : Optional.empty(),
                idempotencyKey);
    }

    public boolean insertPosting(
            String idempotencyKey,
            byte[] requestHash,
            String businessType,
            String businessNo) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO wallet_posting_idempotency (
                      idempotency_key, request_hash, business_type, business_no, created_at
                    ) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, idempotencyKey, requestHash, businessType, businessNo) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public void completePosting(String idempotencyKey, UUID billId) {
        requireOne(jdbcTemplate.update("""
                UPDATE wallet_posting_idempotency
                SET result_bill_id = ?
                WHERE idempotency_key = ? AND result_bill_id IS NULL
                """, uuidToBytes(billId), idempotencyKey));
    }

    public void changeBalance(UUID accountId, long availableDelta, long frozenDelta) {
        int updated = jdbcTemplate.update("""
                UPDATE wallet_account
                SET available_amount_cent = available_amount_cent + ?,
                    frozen_amount_cent = frozen_amount_cent + ?,
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE account_id = ?
                  AND (account_role <> 'CONSUMER_WALLET'
                       OR available_amount_cent + ? >= 0)
                  AND frozen_amount_cent + ? >= 0
                """,
                availableDelta,
                frozenDelta,
                uuidToBytes(accountId),
                availableDelta,
                frozenDelta);
        requireOne(updated);
    }

    public void creditRechargeWithCap(UUID accountId, long amountCent, long maximumCent) {
        int updated = jdbcTemplate.update("""
                UPDATE wallet_account
                SET available_amount_cent = available_amount_cent + ?,
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE account_id = ?
                  AND status = 'ACTIVE'
                  AND available_amount_cent + frozen_amount_cent + ? <= ?
                """,
                amountCent,
                uuidToBytes(accountId),
                amountCent,
                maximumCent);
        requireOne(updated);
    }

    public void insertLedger(
            UUID transactionId,
            String transactionNo,
            String businessType,
            String businessNo,
            long amountCent,
            UUID debitAccountId,
            UUID creditAccountId) {
        jdbcTemplate.update("""
                INSERT INTO ledger_transaction (
                  transaction_id, transaction_no, business_type, business_no,
                  debit_total_amount_cent, credit_total_amount_cent, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(transactionId),
                transactionNo,
                businessType,
                businessNo,
                amountCent,
                amountCent);
        jdbcTemplate.update("""
                INSERT INTO ledger_entry (
                  entry_id, transaction_id, account_id, direction, amount_cent, created_at
                ) VALUES (?, ?, ?, 'DEBIT', ?, UTC_TIMESTAMP(6)),
                         (?, ?, ?, 'CREDIT', ?, UTC_TIMESTAMP(6))
                """,
                uuidToBytes(uuidV7()),
                uuidToBytes(transactionId),
                uuidToBytes(debitAccountId),
                amountCent,
                uuidToBytes(uuidV7()),
                uuidToBytes(transactionId),
                uuidToBytes(creditAccountId),
                amountCent);
    }

    public void updateBillStatus(
            UUID ownerId,
            String businessType,
            String businessNo,
            String expectedStatus,
            String status,
            String failureCode) {
        int updated = jdbcTemplate.update("""
                UPDATE wallet_bill
                SET status = ?, failure_code = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE owner_id = ? AND business_type = ? AND business_no = ?
                  AND status = ?
                """,
                status,
                failureCode,
                uuidToBytes(ownerId),
                businessType,
                businessNo,
                expectedStatus);
        if (updated == 0 && findBillByBusiness(ownerId, businessType, businessNo).isEmpty()) {
            throw new IllegalStateException("Wallet bill not found");
        }
    }

    public void insertBill(
            UUID billId,
            UUID ownerId,
            UUID accountId,
            String businessType,
            String businessNo,
            String direction,
            long amountCent,
            String counterpartyDisplay,
            String remark,
            String status,
            Long balanceAfterCent,
            String failureCode) {
        insertBill(
                billId, ownerId, accountId, businessType, businessNo, direction, amountCent,
                counterpartyDisplay, remark, status, balanceAfterCent, failureCode, null, null);
    }

    public void insertBill(
            UUID billId,
            UUID ownerId,
            UUID accountId,
            String businessType,
            String businessNo,
            String direction,
            long amountCent,
            String counterpartyDisplay,
            String remark,
            String status,
            Long balanceAfterCent,
            String failureCode,
            String source) {
        insertBill(billId, ownerId, accountId, businessType, businessNo, direction,
                amountCent, counterpartyDisplay, remark, status, balanceAfterCent,
                failureCode, source, null);
    }

    public void insertBill(
            UUID billId, UUID ownerId, UUID accountId, String businessType,
            String businessNo, String direction, long amountCent,
            String counterpartyDisplay, String remark, String status,
            Long balanceAfterCent, String failureCode, String source,
            UUID counterpartyUserId) {
        jdbcTemplate.update("""
                INSERT INTO wallet_bill (
                  bill_id, owner_id, account_id, business_type, business_no, source, direction,
                  amount_cent, counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                  failure_code, occurred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                  status = CASE
                    WHEN status IN ('SUCCEEDED', 'FAILED') THEN status
                    ELSE VALUES(status)
                  END,
                  balance_after_cent = COALESCE(VALUES(balance_after_cent), balance_after_cent),
                  failure_code = COALESCE(VALUES(failure_code), failure_code),
                  updated_at = UTC_TIMESTAMP(6)
                """,
                uuidToBytes(billId),
                uuidToBytes(ownerId),
                uuidToBytes(accountId),
                businessType,
                businessNo,
                source,
                direction,
                amountCent,
                counterpartyDisplay,
                uuidToBytes(counterpartyUserId),
                remark,
                status,
                balanceAfterCent,
                failureCode);
    }

    public Optional<WalletBill> findBill(UUID ownerId, UUID billId) {
        return jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        WHERE owner_id = ? AND bill_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(BILL_MAPPER.mapRow(resultSet, 0))
                        : Optional.empty(),
                uuidToBytes(ownerId),
                uuidToBytes(billId));
    }

    public Optional<WalletBill> findBillByBusiness(
            UUID ownerId, String businessType, String businessNo) {
        return jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        WHERE owner_id = ? AND business_type = ? AND business_no = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(BILL_MAPPER.mapRow(resultSet, 0))
                        : Optional.empty(),
                uuidToBytes(ownerId),
                businessType,
                businessNo);
    }

    public List<WalletBill> recentBills(UUID ownerId, int size) {
        return jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        WHERE owner_id = ?
                        ORDER BY occurred_at DESC, bill_id DESC
                        LIMIT ?
                        """, BILL_MAPPER, uuidToBytes(ownerId), size);
    }

    public BillPage queryBills(UUID ownerId, BillQuery query) {
        StringBuilder where = new StringBuilder(" WHERE owner_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(uuidToBytes(ownerId));
        appendFilter(where, arguments, "occurred_at >= ?", query.from() == null
                ? null : Timestamp.from(query.from()));
        appendFilter(where, arguments, "occurred_at <= ?", query.to() == null
                ? null : Timestamp.from(query.to()));
        appendFilter(where, arguments, "direction = ?", query.direction());
        appendFilter(where, arguments, "business_type = ?", query.businessType());
        appendFilter(where, arguments, "source = ?", query.source());
        appendFilter(where, arguments, "status = ?", query.status());

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_bill" + where,
                Long.class,
                arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(query.size());
        pageArguments.add((query.page() - 1) * query.size());
        List<WalletBill> items = jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        """ + where + " ORDER BY occurred_at DESC, bill_id DESC LIMIT ? OFFSET ?",
                BILL_MAPPER,
                pageArguments.toArray());
        return new BillPage(items, query.page(), query.size(), total);
    }

    public CollectionRecordPage queryCollectionRecords(
            UUID ownerId, String type, String period, Instant from, Instant to,
            int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        String typePredicate = switch (type) {
            case "PERSONAL" -> "(direction = 'INCOME' AND source = 'PERSONAL_COLLECTION_CODE')";
            case "MERCHANT" -> "((direction = 'INCOME' AND source = 'MERCHANT_PAYMENT') OR "
                    + "(direction = 'EXPENSE' AND source = 'MERCHANT_REFUND'))";
            default -> "((direction = 'INCOME' AND source IN ('PERSONAL_COLLECTION_CODE', 'MERCHANT_PAYMENT')) OR "
                    + "(direction = 'EXPENSE' AND source = 'MERCHANT_REFUND'))";
        };
        String where = " WHERE owner_id = ? AND status = 'SUCCEEDED' AND occurred_at >= ? "
                + "AND occurred_at < ? AND " + typePredicate;
        Object[] arguments = {uuidToBytes(ownerId), Timestamp.from(from), Timestamp.from(to)};
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_bill" + where, Long.class, arguments);
        List<WalletBill> items = jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        """ + where + " ORDER BY occurred_at DESC, bill_id DESC LIMIT ? OFFSET ?",
                BILL_MAPPER, uuidToBytes(ownerId), Timestamp.from(from), Timestamp.from(to),
                normalizedSize, (normalizedPage - 1) * normalizedSize);
        CollectionRecordPage.Summary summary = jdbcTemplate.queryForObject("""
                        SELECT
                          COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN 1 ELSE 0 END), 0) collection_count,
                          COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amount_cent ELSE 0 END), 0) collection_amount,
                          COALESCE(SUM(CASE WHEN source = 'MERCHANT_REFUND' THEN 1 ELSE 0 END), 0) refund_count,
                          COALESCE(SUM(CASE WHEN source = 'MERCHANT_REFUND' THEN amount_cent ELSE 0 END), 0) refund_amount
                        FROM wallet_bill
                        """ + where,
                (rs, row) -> {
                    long collected = rs.getLong("collection_amount");
                    long refunded = rs.getLong("refund_amount");
                    return new CollectionRecordPage.Summary(
                            rs.getLong("collection_count"), collected,
                            rs.getLong("refund_count"), refunded, collected - refunded);
                }, arguments);
        return new CollectionRecordPage(items, normalizedPage, normalizedSize, total,
                type, period, from, to, summary);
    }

    public TransferRecordRows queryTransferRecords(
            UUID ownerId, UUID counterpartyUserId, String direction, String status,
            Instant from, Instant to, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(
                " WHERE owner_id = ? AND business_type = 'TRANSFER' AND counterparty_user_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(uuidToBytes(ownerId));
        arguments.add(uuidToBytes(counterpartyUserId));
        appendFilter(where, arguments, "direction = ?", direction);
        appendFilter(where, arguments, "status = ?", status);
        appendFilter(where, arguments, "occurred_at >= ?", from == null ? null : Timestamp.from(from));
        appendFilter(where, arguments, "occurred_at < ?", to == null ? null : Timestamp.from(to));

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_bill" + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(normalizedSize);
        pageArguments.add((normalizedPage - 1) * normalizedSize);
        List<WalletBill> items = jdbcTemplate.query("""
                        SELECT bill_id, business_type, business_no, source, direction, amount_cent,
                               counterparty_display, counterparty_user_id, remark, status, balance_after_cent,
                               failure_code, occurred_at, updated_at
                        FROM wallet_bill
                        """ + where + " ORDER BY occurred_at DESC, bill_id DESC LIMIT ? OFFSET ?",
                BILL_MAPPER, pageArguments.toArray());

        List<TransferMonthRow> months;
        if (status != null && !"SUCCEEDED".equals(status)) {
            months = List.of();
        } else {
            StringBuilder totalsWhere = new StringBuilder(
                    " WHERE owner_id = ? AND business_type = 'TRANSFER' AND status = 'SUCCEEDED' AND counterparty_user_id = ?");
            List<Object> totalsArguments = new ArrayList<>();
            totalsArguments.add(uuidToBytes(ownerId));
            totalsArguments.add(uuidToBytes(counterpartyUserId));
            appendFilter(totalsWhere, totalsArguments, "direction = ?", direction);
            appendFilter(totalsWhere, totalsArguments, "occurred_at >= ?", from == null ? null : Timestamp.from(from));
            appendFilter(totalsWhere, totalsArguments, "occurred_at < ?", to == null ? null : Timestamp.from(to));
            months = jdbcTemplate.query("""
                            SELECT DATE_FORMAT(DATE_ADD(occurred_at, INTERVAL 8 HOUR), '%Y-%m') AS transfer_month,
                                   COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amount_cent ELSE 0 END), 0) AS income_cent,
                                   COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN amount_cent ELSE 0 END), 0) AS expense_cent
                            FROM wallet_bill
                            """ + totalsWhere + " GROUP BY transfer_month ORDER BY transfer_month DESC",
                    (rs, row) -> new TransferMonthRow(rs.getString("transfer_month"),
                            rs.getLong("income_cent"), rs.getLong("expense_cent")), totalsArguments.toArray());
        }
        return new TransferRecordRows(items, normalizedPage, normalizedSize, total, months);
    }

    public RecentCounterpartyRows queryRecentTransferCounterparties(UUID ownerId, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT counterparty_user_id) FROM wallet_bill
                WHERE owner_id = ? AND business_type = 'TRANSFER' AND status = 'SUCCEEDED'
                  AND counterparty_user_id IS NOT NULL
                """, Long.class, uuidToBytes(ownerId));
        List<RecentCounterpartyRow> items = jdbcTemplate.query("""
                        SELECT counterparty_user_id, MAX(counterparty_display) AS counterparty_display,
                               MAX(occurred_at) AS last_transfer_at
                        FROM wallet_bill
                        WHERE owner_id = ? AND business_type = 'TRANSFER' AND status = 'SUCCEEDED'
                          AND counterparty_user_id IS NOT NULL
                        GROUP BY counterparty_user_id
                        ORDER BY last_transfer_at DESC, counterparty_user_id DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, row) -> new RecentCounterpartyRow(
                        bytesToUuid(rs.getBytes("counterparty_user_id")),
                        rs.getString("counterparty_display"),
                        rs.getTimestamp("last_transfer_at").toInstant()),
                uuidToBytes(ownerId), normalizedSize, (normalizedPage - 1) * normalizedSize);
        return new RecentCounterpartyRows(items, normalizedPage, normalizedSize, total);
    }

    public List<BillAggregateRow> aggregateBills(UUID ownerId, BillQuery query) {
        StringBuilder where = new StringBuilder(" WHERE b.owner_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(uuidToBytes(ownerId));
        appendFilter(where, arguments, "b.occurred_at >= ?", query.from() == null
                ? null : Timestamp.from(query.from()));
        appendFilter(where, arguments, "b.occurred_at <= ?", query.to() == null
                ? null : Timestamp.from(query.to()));
        appendFilter(where, arguments, "b.direction = ?", query.direction());
        appendFilter(where, arguments, "b.business_type = ?", query.businessType());
        appendFilter(where, arguments, "b.source = ?", query.source());
        appendFilter(where, arguments, "b.status = ?", query.status());
        where.append(" AND COALESCE(m.include_in_statistics, TRUE) = TRUE");
        return jdbcTemplate.query("""
                        SELECT b.direction, b.business_type, COUNT(*) AS bill_count,
                               COALESCE(SUM(b.amount_cent), 0) AS total_amount_cent
                        FROM wallet_bill b
                        LEFT JOIN wallet_bill_management m
                          ON m.bill_id = b.bill_id AND m.owner_id = b.owner_id
                        """ + where + " GROUP BY b.direction, b.business_type ORDER BY b.direction, b.business_type LIMIT 100",
                (rs, row) -> new BillAggregateRow(
                        rs.getString("direction"), rs.getString("business_type"),
                        rs.getLong("bill_count"), rs.getLong("total_amount_cent")),
                arguments.toArray());
    }

    public Optional<BillManagementRow> findBillManagement(UUID ownerId, UUID billId) {
        return jdbcTemplate.query("""
                        SELECT category_code, user_note, include_in_statistics
                        FROM wallet_bill_management
                        WHERE owner_id = ? AND bill_id = ?
                        """, rs -> rs.next() ? Optional.of(new BillManagementRow(
                        rs.getString("category_code"), rs.getString("user_note"),
                        rs.getBoolean("include_in_statistics"))) : Optional.empty(),
                uuidToBytes(ownerId), uuidToBytes(billId));
    }

    public void upsertBillManagement(
            UUID ownerId, UUID billId, String categoryCode, String userNote,
            boolean includedInStatistics) {
        jdbcTemplate.update("""
                INSERT INTO wallet_bill_management (
                  bill_id, owner_id, category_code, user_note, include_in_statistics,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE category_code = VALUES(category_code),
                  user_note = VALUES(user_note),
                  include_in_statistics = VALUES(include_in_statistics),
                  updated_at = UTC_TIMESTAMP(6)
                """, uuidToBytes(billId), uuidToBytes(ownerId), categoryCode, userNote,
                includedInStatistics);
    }

    public List<BillTag> findBillTags(UUID ownerId, UUID billId) {
        return jdbcTemplate.query("""
                        SELECT t.tag_id, t.name, t.created_at
                        FROM wallet_bill_tag_assignment a
                        JOIN wallet_user_tag t ON t.tag_id = a.tag_id AND t.owner_id = a.owner_id
                        WHERE a.owner_id = ? AND a.bill_id = ?
                        ORDER BY a.created_at, t.tag_id
                        """, (rs, row) -> new BillTag(bytesToUuid(rs.getBytes("tag_id")),
                        rs.getString("name"), rs.getTimestamp("created_at").toInstant()),
                uuidToBytes(ownerId), uuidToBytes(billId));
    }

    public int countOwnedTags(UUID ownerId, List<UUID> tagIds) {
        if (tagIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(tagIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(uuidToBytes(ownerId));
        tagIds.forEach(id -> args.add(uuidToBytes(id)));
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_user_tag WHERE owner_id = ? AND tag_id IN ("
                        + placeholders + ")", Integer.class, args.toArray());
    }

    public void replaceBillTags(UUID ownerId, UUID billId, List<UUID> tagIds) {
        jdbcTemplate.update("DELETE FROM wallet_bill_tag_assignment WHERE owner_id = ? AND bill_id = ?",
                uuidToBytes(ownerId), uuidToBytes(billId));
        for (UUID tagId : tagIds) {
            jdbcTemplate.update("""
                    INSERT INTO wallet_bill_tag_assignment (owner_id, bill_id, tag_id, created_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(ownerId), uuidToBytes(billId), uuidToBytes(tagId));
        }
    }

    public BillTagPageRow queryTags(UUID ownerId, int page, int size) {
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_user_tag WHERE owner_id = ?", Long.class,
                uuidToBytes(ownerId));
        List<BillTag> items = jdbcTemplate.query("""
                        SELECT tag_id, name, created_at FROM wallet_user_tag
                        WHERE owner_id = ? ORDER BY created_at DESC, tag_id DESC LIMIT ? OFFSET ?
                        """, (rs, row) -> new BillTag(bytesToUuid(rs.getBytes("tag_id")),
                        rs.getString("name"), rs.getTimestamp("created_at").toInstant()),
                uuidToBytes(ownerId), size, (page - 1) * size);
        return new BillTagPageRow(items, total);
    }

    public Optional<TagIdempotencyRow> findTagByIdempotency(UUID ownerId, String key) {
        return jdbcTemplate.query("""
                        SELECT tag_id, name, request_hash, created_at FROM wallet_user_tag
                        WHERE owner_id = ? AND idempotency_key = ?
                        """, rs -> rs.next() ? Optional.of(new TagIdempotencyRow(
                        new BillTag(bytesToUuid(rs.getBytes("tag_id")), rs.getString("name"),
                                rs.getTimestamp("created_at").toInstant()),
                        rs.getBytes("request_hash"))) : Optional.empty(),
                uuidToBytes(ownerId), key);
    }

    public long countTags(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_user_tag WHERE owner_id = ?", Long.class,
                uuidToBytes(ownerId));
    }

    public BillTag insertTag(
            UUID tagId, UUID ownerId, String name, String normalizedName,
            String idempotencyKey, byte[] requestHash) {
        jdbcTemplate.update("""
                INSERT INTO wallet_user_tag (
                  tag_id, owner_id, name, normalized_name, idempotency_key, request_hash,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uuidToBytes(tagId), uuidToBytes(ownerId), name, normalizedName,
                idempotencyKey, requestHash);
        return findTagByIdempotency(ownerId, idempotencyKey).orElseThrow().tag();
    }

    public boolean insertFreeze(
            UUID freezeId,
            String xid,
            long branchId,
            String businessNo,
            String source,
            UUID accountId,
            UUID counterpartyUserId,
            long amountCent,
            String annualLimitMode,
            Integer limitYear,
            long reservedLimitAmountCent) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO account_freeze (
                      freeze_id, xid, branch_id, business_no, source, account_id, counterparty_user_id, amount_cent,
                      annual_limit_mode, limit_year, reserved_limit_amount_cent,
                      status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TRY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """,
                    uuidToBytes(freezeId), xid, branchId, businessNo, source,
                    uuidToBytes(accountId), uuidToBytes(counterpartyUserId), amountCent, annualLimitMode,
                    limitYear, reservedLimitAmountCent) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<BranchRow> lockFreeze(String xid, long branchId) {
        return jdbcTemplate.query("""
                SELECT freeze_id, business_no, source, account_id, counterparty_user_id, amount_cent, status,
                       annual_limit_mode, limit_year, reserved_limit_amount_cent
                FROM account_freeze WHERE xid = ? AND branch_id = ? FOR UPDATE
                """, rs -> rs.next() ? Optional.of(new BranchRow(
                bytesToUuid(rs.getBytes("freeze_id")), rs.getString("business_no"), rs.getString("source"),
                bytesToUuid(rs.getBytes("account_id")), bytesToUuid(rs.getBytes("counterparty_user_id")), rs.getLong("amount_cent"),
                rs.getString("status"), rs.getString("annual_limit_mode"),
                (Integer) rs.getObject("limit_year"),
                rs.getLong("reserved_limit_amount_cent"))) : Optional.empty(), xid, branchId);
    }

    public boolean insertPendingCredit(
            UUID creditId,
            String xid,
            long branchId,
            String businessNo,
            String source,
            UUID accountId,
            UUID counterpartyUserId,
            long amountCent) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO pending_credit (
                      credit_id, xid, branch_id, business_no, source, account_id, counterparty_user_id, amount_cent,
                      status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TRY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """,
                    uuidToBytes(creditId), xid, branchId, businessNo, source,
                    uuidToBytes(accountId), uuidToBytes(counterpartyUserId), amountCent) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<BranchRow> lockPendingCredit(String xid, long branchId) {
        return lockBranch("pending_credit", "credit_id", xid, branchId);
    }

    public Optional<BranchRow> findFreezeByBusiness(
            String businessNo, UUID accountId) {
        return jdbcTemplate.query("""
                SELECT freeze_id, business_no, source, account_id, counterparty_user_id, amount_cent, status,
                       annual_limit_mode, limit_year, reserved_limit_amount_cent
                FROM account_freeze WHERE business_no = ? AND account_id = ?
                """, rs -> rs.next() ? Optional.of(new BranchRow(
                bytesToUuid(rs.getBytes("freeze_id")), rs.getString("business_no"), rs.getString("source"),
                bytesToUuid(rs.getBytes("account_id")), bytesToUuid(rs.getBytes("counterparty_user_id")), rs.getLong("amount_cent"),
                rs.getString("status"), rs.getString("annual_limit_mode"),
                (Integer) rs.getObject("limit_year"),
                rs.getLong("reserved_limit_amount_cent"))) : Optional.empty(),
                businessNo, uuidToBytes(accountId));
    }

    public Optional<BranchRow> findPendingCreditByBusiness(
            String businessNo, UUID accountId) {
        return findBranchByBusiness(
                "pending_credit", "credit_id", businessNo, accountId);
    }

    public void updateBranchStatus(String table, String xid, long branchId, String status) {
        if (!"account_freeze".equals(table) && !"pending_credit".equals(table)) {
            throw new IllegalArgumentException("Unsupported TCC branch table");
        }
        requireOne(jdbcTemplate.update("""
                UPDATE %s
                SET status = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE xid = ? AND branch_id = ? AND status = 'TRY'
                """.formatted(table), status, xid, branchId));
    }

    private Optional<BranchRow> lockBranch(
            String table, String idColumn, String xid, long branchId) {
        String sql = """
                SELECT %s, business_no, source, account_id, counterparty_user_id, amount_cent, status
                FROM %s
                WHERE xid = ? AND branch_id = ?
                FOR UPDATE
                """.formatted(idColumn, table);
        return jdbcTemplate.query(sql,
                resultSet -> resultSet.next()
                        ? Optional.of(new BranchRow(
                        bytesToUuid(resultSet.getBytes(idColumn)),
                        resultSet.getString("business_no"), resultSet.getString("source"),
                        bytesToUuid(resultSet.getBytes("account_id")),
                        bytesToUuid(resultSet.getBytes("counterparty_user_id")),
                        resultSet.getLong("amount_cent"),
                        resultSet.getString("status"), "EXEMPT_ACTIVE_CARD", null, 0))
                        : Optional.empty(),
                xid, branchId);
    }

    private Optional<BranchRow> findBranchByBusiness(
            String table, String idColumn, String businessNo, UUID accountId) {
        if ((!"account_freeze".equals(table) && !"pending_credit".equals(table))
                || (!"freeze_id".equals(idColumn) && !"credit_id".equals(idColumn))) {
            throw new IllegalArgumentException("Unsupported TCC branch table");
        }
        String sql = """
                SELECT %s, business_no, source, account_id, counterparty_user_id, amount_cent, status
                FROM %s
                WHERE business_no = ? AND account_id = ?
                """.formatted(idColumn, table);
        return jdbcTemplate.query(sql,
                resultSet -> resultSet.next()
                        ? Optional.of(new BranchRow(
                        bytesToUuid(resultSet.getBytes(idColumn)),
                        resultSet.getString("business_no"), resultSet.getString("source"),
                        bytesToUuid(resultSet.getBytes("account_id")),
                        bytesToUuid(resultSet.getBytes("counterparty_user_id")),
                        resultSet.getLong("amount_cent"),
                        resultSet.getString("status"), "EXEMPT_ACTIVE_CARD", null, 0))
                        : Optional.empty(),
                businessNo,
                uuidToBytes(accountId));
    }

    private Optional<AccountRow> queryConsumerAccount(UUID ownerId, boolean lock) {
        String sql = """
                SELECT account_id, owner_id, available_amount_cent, frozen_amount_cent,
                       currency, status
                FROM wallet_account
                WHERE owner_type = 'CONSUMER' AND owner_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql,
                resultSet -> resultSet.next()
                        ? Optional.of(new AccountRow(
                        bytesToUuid(resultSet.getBytes("account_id")),
                        bytesToUuid(resultSet.getBytes("owner_id")),
                        resultSet.getLong("available_amount_cent"),
                        resultSet.getLong("frozen_amount_cent"),
                        resultSet.getString("currency"),
                        resultSet.getString("status")))
                        : Optional.empty(),
                uuidToBytes(ownerId));
    }

    private static void appendFilter(
            StringBuilder sql, List<Object> arguments, String clause, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            sql.append(" AND ").append(clause);
            arguments.add(value);
        }
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new IllegalStateException("Expected one wallet row to change, got " + count);
        }
    }

    public static byte[] uuidToBytes(UUID value) {
        if (value == null) return null;
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static UUID bytesToUuid(byte[] value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record BillAggregateRow(
            String direction, String businessType, long billCount, long totalAmountCent) {
    }

    public record TransferMonthRow(String month, long incomeAmountCent, long expenseAmountCent) {}
    public record TransferRecordRows(List<WalletBill> items, int page, int size, long total,
                                     List<TransferMonthRow> months) {}
    public record RecentCounterpartyRow(UUID userId, String displayName, Instant lastTransferAt) {}
    public record RecentCounterpartyRows(List<RecentCounterpartyRow> items, int page, int size, long total) {}

    public record BillManagementRow(
            String categoryCode, String userNote, boolean includedInStatistics) {
    }

    public record BillTagPageRow(List<BillTag> items, long total) {
    }

    public record TagIdempotencyRow(BillTag tag, byte[] requestHash) {
    }

    private static UUID uuidV7() {
        long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long randomMost = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        long randomLeast = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        long most = (timestamp << 16) | 0x7000L | (randomMost & 0x0FFFL);
        long least = (randomLeast & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static final RowMapper<WalletBill> BILL_MAPPER = (resultSet, rowNumber) ->
            new WalletBill(
                    bytesToUuid(resultSet.getBytes("bill_id")),
                    resultSet.getString("business_type"),
                    resultSet.getString("business_no"),
                    resultSet.getString("source"),
                    resultSet.getString("direction"),
                    resultSet.getLong("amount_cent"),
                    resultSet.getString("counterparty_display"),
                    bytesToUuid(resultSet.getBytes("counterparty_user_id")),
                    null,
                    resultSet.getString("remark"),
                    resultSet.getString("status"),
                    resultSet.getObject("balance_after_cent", Long.class),
                    resultSet.getString("failure_code"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private static LedgerTransactionRow mapLedgerTransaction(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new LedgerTransactionRow(
                bytesToUuid(resultSet.getBytes("transaction_id")),
                resultSet.getString("transaction_no"),
                resultSet.getString("business_type"),
                resultSet.getString("business_no"),
                resultSet.getLong("debit_total_amount_cent"),
                resultSet.getLong("credit_total_amount_cent"),
                resultSet.getTimestamp("occurred_at").toInstant());
    }

    public record AccountRow(
            UUID accountId,
            UUID ownerId,
            long availableAmountCent,
            long frozenAmountCent,
            String currency,
            String status) {
    }

    public record OpsAccountRow(
            UUID accountId,
            String accountNo,
            String ownerType,
            UUID ownerId,
            String currency,
            String accountRole,
            long availableAmountCent,
            long frozenAmountCent,
            String status,
            Instant updatedAt) {
    }

    public record LedgerTransactionRow(
            UUID transactionId,
            String transactionNo,
            String businessType,
            String businessNo,
            long debitTotalAmountCent,
            long creditTotalAmountCent,
            Instant occurredAt) {
    }

    public record LedgerEntryRow(
            UUID entryId,
            UUID transactionId,
            UUID accountId,
            String direction,
            long amountCent,
            Instant createdAt) {
    }

    public record PostingRow(
            byte[] requestHash,
            String businessType,
            String businessNo,
            UUID resultBillId) {
    }

    public record BranchRow(
            UUID branchId,
            String businessNo,
            String source,
            UUID accountId,
            UUID counterpartyUserId,
            long amountCent,
            String status,
            String annualLimitMode,
            Integer limitYear,
            long reservedLimitAmountCent) {
        public BranchRow(
                UUID branchId, String businessNo, String source, UUID accountId,
                long amountCent, String status, String annualLimitMode,
                Integer limitYear, long reservedLimitAmountCent) {
            this(branchId, businessNo, source, accountId, null, amountCent, status,
                    annualLimitMode, limitYear, reservedLimitAmountCent);
        }
    }
}
