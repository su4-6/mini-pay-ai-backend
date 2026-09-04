package com.minipay.wallet.infrastructure.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WalletFinancialMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(WalletFinancialMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong tryBranches = new AtomicLong();
    private final AtomicLong exceptionalBranches = new AtomicLong();
    private final AtomicLong unbalancedLedgers = new AtomicLong();
    private final AtomicLong clearingBalanceCent = new AtomicLong();

    public WalletFinancialMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("minipay_wallet_tcc_try_branches", tryBranches, AtomicLong::get)
                .register(meters);
        Gauge.builder("minipay_wallet_tcc_exceptional_branches", exceptionalBranches,
                        AtomicLong::get)
                .register(meters);
        Gauge.builder("minipay_wallet_unbalanced_ledgers", unbalancedLedgers, AtomicLong::get)
                .register(meters);
        Gauge.builder("minipay_wallet_transfer_clearing_balance_cent", clearingBalanceCent,
                        AtomicLong::get)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${minipay.metrics.refresh-delay-ms:10000}")
    public void refresh() {
        tryBranches.set(count("""
                SELECT (SELECT COUNT(*) FROM account_freeze WHERE status = 'TRY')
                     + (SELECT COUNT(*) FROM pending_credit WHERE status = 'TRY')
                """));
        exceptionalBranches.set(count("""
                SELECT COUNT(*) FROM (
                  SELECT business_no FROM (
                    SELECT business_no, updated_at FROM account_freeze
                    WHERE status = 'CONFIRMED'
                    UNION ALL
                    SELECT business_no, updated_at FROM pending_credit
                    WHERE status = 'CONFIRMED'
                  ) b GROUP BY business_no
                  HAVING COUNT(*) = 1
                     AND MAX(updated_at) < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)
                ) one_sided
                """));
        long unbalanced = count("""
                SELECT COUNT(*) FROM ledger_transaction
                WHERE debit_total_amount_cent <> credit_total_amount_cent
                """);
        unbalancedLedgers.set(unbalanced);
        Long clearing = jdbc.queryForObject("""
                SELECT available_amount_cent FROM wallet_account
                WHERE account_id = UUID_TO_BIN('00000000-0000-7000-8000-000000000002')
                """, Long.class);
        clearingBalanceCent.set(clearing == null ? 0 : clearing);
        if (unbalanced > 0 || clearingBalanceCent.get() != 0 || exceptionalBranches.get() > 0) {
            LOG.error("Wallet reconciliation alert: unbalanced={}, clearingCent={}, branches={}",
                    unbalanced, clearingBalanceCent.get(), exceptionalBranches.get());
        }
    }

    private long count(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
