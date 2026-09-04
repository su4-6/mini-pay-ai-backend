package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Idempotently provisions one shared personal wallet per merchant owner. */
@Component
public class MerchantWalletProvisioner {
    private final JdbcTemplate jdbc;
    private final WalletInternalClient wallets;

    public MerchantWalletProvisioner(JdbcTemplate jdbc, WalletInternalClient wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
    }

    public void register(UUID ownerUserId) {
        jdbc.update("""
                INSERT IGNORE INTO merchant_owner_wallet_provision
                  (owner_user_id, status, attempts, created_at, updated_at)
                VALUES (?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, PaymentRepository.uuidToBytes(ownerUserId));
        attempt(ownerUserId);
    }

    @Scheduled(fixedDelayString = "${minipay.merchant-wallet-provision.retry-delay:PT30S}")
    public void retryPending() {
        jdbc.update("""
                INSERT IGNORE INTO merchant_owner_wallet_provision
                  (owner_user_id, status, attempts, created_at, updated_at)
                SELECT DISTINCT owner_user_id, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM merchant WHERE owner_user_id IS NOT NULL
                """);
        List<UUID> owners = jdbc.query("""
                SELECT owner_user_id FROM merchant_owner_wallet_provision
                WHERE status <> 'PROVISIONED'
                  AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6))
                ORDER BY updated_at LIMIT 50
                """, (rs, ignored) -> uuid(rs.getBytes("owner_user_id")));
        owners.forEach(this::attempt);
    }

    private void attempt(UUID ownerUserId) {
        try {
            // The owner id is both the stable event id and wallet owner key.
            wallets.openWallet(ownerUserId, ownerUserId);
            jdbc.update("""
                    UPDATE merchant_owner_wallet_provision
                    SET status='PROVISIONED', provisioned_at=UTC_TIMESTAMP(6),
                        last_error_code=NULL, next_retry_at=NULL, updated_at=UTC_TIMESTAMP(6)
                    WHERE owner_user_id=?
                    """, PaymentRepository.uuidToBytes(ownerUserId));
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE merchant_owner_wallet_provision
                    SET status='RETRY', attempts=attempts+1,
                        next_retry_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND),
                        last_error_code='UPSTREAM_UNAVAILABLE', updated_at=UTC_TIMESTAMP(6)
                    WHERE owner_user_id=?
                    """, PaymentRepository.uuidToBytes(ownerUserId));
        }
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
