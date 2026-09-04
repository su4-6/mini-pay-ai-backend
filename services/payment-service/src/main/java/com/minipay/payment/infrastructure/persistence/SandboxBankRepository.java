package com.minipay.payment.infrastructure.persistence;

import com.minipay.payment.application.service.UuidV7;
import com.minipay.payment.domain.model.BankTransaction;
import com.minipay.payment.domain.model.BankTransactionPage;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SandboxBankRepository {
    private final JdbcTemplate jdbc;

    public SandboxBankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize(
            String providerToken,
            long initialBalanceCent,
            long singlePaymentLimitCent,
            long dailyPaymentLimitCent) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO sandbox_bank_account (
                  provider_token, available_amount_cent, currency,
                  single_payment_limit_cent, daily_payment_limit_cent,
                  created_at, updated_at
                ) VALUES (?, ?, 'CNY', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, providerToken, initialBalanceCent,
                singlePaymentLimitCent, dailyPaymentLimitCent);
        if (inserted == 1) {
            insertTransaction(
                    providerToken,
                    "ACCOUNT-OPENING",
                    "ACCOUNT_OPENING",
                    "INCOME",
                    "沙箱银行账户初始化",
                    initialBalanceCent,
                    "SUCCEEDED",
                    null);
        }
    }

    public Optional<AccountRow> lockAccount(String providerToken) {
        return jdbc.query("""
                        SELECT available_amount_cent, currency,
                               single_payment_limit_cent, daily_payment_limit_cent,
                               updated_at
                        FROM sandbox_bank_account
                        WHERE provider_token = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new AccountRow(
                        resultSet.getLong("available_amount_cent"),
                        resultSet.getString("currency"),
                        resultSet.getLong("single_payment_limit_cent"),
                        resultSet.getLong("daily_payment_limit_cent"),
                        resultSet.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                providerToken);
    }

    public Optional<AccountRow> findAccount(String providerToken) {
        return jdbc.query("""
                        SELECT available_amount_cent, currency,
                               single_payment_limit_cent, daily_payment_limit_cent,
                               updated_at
                        FROM sandbox_bank_account
                        WHERE provider_token = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new AccountRow(
                        resultSet.getLong("available_amount_cent"),
                        resultSet.getString("currency"),
                        resultSet.getLong("single_payment_limit_cent"),
                        resultSet.getLong("daily_payment_limit_cent"),
                        resultSet.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                providerToken);
    }

    public Optional<TransactionRow> findByRequest(String providerToken, String requestNo) {
        return jdbc.query("""
                        SELECT transaction_id, status, request_no, failure_code
                        FROM sandbox_bank_transaction
                        WHERE provider_token = ? AND request_no = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new TransactionRow(
                        PaymentRepository.bytesToUuid(resultSet.getBytes("transaction_id")),
                        resultSet.getString("status"),
                        resultSet.getString("request_no"),
                        resultSet.getString("failure_code")))
                        : Optional.empty(),
                providerToken,
                requestNo);
    }

    public void updateBalance(String providerToken, long availableAmountCent) {
        int changed = jdbc.update("""
                UPDATE sandbox_bank_account
                SET available_amount_cent = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE provider_token = ?
                """, availableAmountCent, providerToken);
        if (changed != 1) {
            throw new IllegalStateException("Expected one sandbox bank account to change");
        }
    }

    public TransactionRow insertTransaction(
            String providerToken,
            String requestNo,
            String transactionType,
            String direction,
            String description,
            long amountCent,
            String status,
            String failureCode) {
        var transactionId = UuidV7.generate();
        jdbc.update("""
                INSERT INTO sandbox_bank_transaction (
                  transaction_id, provider_token, request_no, transaction_type,
                  direction, description, amount_cent, status, failure_code,
                  occurred_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                PaymentRepository.uuidToBytes(transactionId),
                providerToken,
                requestNo,
                transactionType,
                direction,
                description,
                amountCent,
                status,
                failureCode);
        return new TransactionRow(transactionId, status, requestNo, failureCode);
    }

    public long dailyExpenseCent(String providerToken) {
        Long amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_cent), 0)
                FROM sandbox_bank_transaction
                WHERE provider_token = ? AND direction = 'EXPENSE'
                  AND status = 'SUCCEEDED'
                  AND occurred_at >= UTC_DATE()
                  AND occurred_at < UTC_DATE() + INTERVAL 1 DAY
                """, Long.class, providerToken);
        return amount == null ? 0L : amount;
    }

    public BankTransactionPage transactions(
            String providerToken, Instant from, Instant to, int page, int size) {
        String range = (from == null ? "" : " AND occurred_at >= ?")
                + (to == null ? "" : " AND occurred_at < ?");
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(providerToken);
        if (from != null) parameters.add(java.sql.Timestamp.from(from));
        if (to != null) parameters.add(java.sql.Timestamp.from(to));
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sandbox_bank_transaction WHERE provider_token = ?" + range,
                Long.class,
                parameters.toArray());
        parameters.add(size);
        parameters.add((page - 1) * size);
        var items = jdbc.query("""
                        SELECT transaction_id, transaction_type, direction, description,
                               amount_cent, status, occurred_at
                        FROM sandbox_bank_transaction
                        WHERE provider_token = ?
                        """ + range + " ORDER BY occurred_at DESC, transaction_id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new BankTransaction(
                        PaymentRepository.bytesToUuid(resultSet.getBytes("transaction_id")),
                        resultSet.getString("transaction_type"),
                        resultSet.getString("direction"),
                        resultSet.getString("description"),
                        resultSet.getLong("amount_cent"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("occurred_at").toInstant()),
                parameters.toArray());
        return new BankTransactionPage(items, page, size, total == null ? 0L : total);
    }

    public record AccountRow(
            long availableAmountCent,
            String currency,
            long singlePaymentLimitCent,
            long dailyPaymentLimitCent,
            Instant updatedAt) {
    }

    public record TransactionRow(
            java.util.UUID transactionId,
            String status,
            String requestNo,
            String failureCode) {
    }
}
