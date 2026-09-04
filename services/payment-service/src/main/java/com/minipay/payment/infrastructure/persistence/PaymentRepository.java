package com.minipay.payment.infrastructure.persistence;

import com.minipay.payment.domain.model.BankCard;
import com.minipay.payment.domain.model.PaymentOrder;
import com.minipay.payment.domain.model.RechargeOrder;
import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.domain.model.TransferIntent;
import com.minipay.payment.domain.model.TransferOrder;
import com.minipay.payment.domain.model.WithdrawalOrder;
import com.minipay.payment.domain.model.FundingOrderListItem;
import com.minipay.payment.domain.model.FundingOrderPage;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<BankCardRow> findBankCardRow(UUID userId, UUID cardId, boolean lock) {
        String sql = """
                SELECT card_id, user_id, provider, provider_token, bank_name, card_type,
                       masked_card_no, last_four, holder_name, status, verified_at
                FROM bank_card
                WHERE user_id = ? AND card_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql,
                resultSet -> resultSet.next()
                        ? Optional.of(new BankCardRow(
                        bytesToUuid(resultSet.getBytes("card_id")),
                        bytesToUuid(resultSet.getBytes("user_id")),
                        resultSet.getString("provider"),
                        resultSet.getString("provider_token"),
                        resultSet.getString("bank_name"),
                        resultSet.getString("card_type"),
                        resultSet.getString("masked_card_no"),
                        resultSet.getString("last_four"),
                        resultSet.getString("holder_name"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("verified_at").toInstant()))
                        : Optional.empty(),
                uuidToBytes(userId),
                uuidToBytes(cardId));
    }

    public Optional<BankCard> findBankCardByProviderToken(UUID userId, String providerToken) {
        return jdbcTemplate.query("""
                        SELECT card_id, bank_name, card_type, masked_card_no, status, verified_at
                        FROM bank_card
                        WHERE user_id = ? AND provider_token = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapBankCard(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                providerToken);
    }

    public BankCard insertBankCard(
            UUID cardId,
            UUID userId,
            String provider,
            String providerToken,
            String bankName,
            String cardType,
            String maskedCardNo,
            String lastFour,
            String holderName) {
        jdbcTemplate.update("""
                INSERT INTO bank_card (
                  card_id, user_id, provider, provider_token, bank_name, card_type,
                  masked_card_no, last_four, holder_name, status, verified_at,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(cardId),
                uuidToBytes(userId),
                provider,
                providerToken,
                bankName,
                cardType,
                maskedCardNo,
                lastFour,
                holderName);
        return findBankCardRow(userId, cardId, false).orElseThrow().toPublicModel();
    }

    public List<BankCard> listBankCards(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT card_id, bank_name, card_type, masked_card_no, status, verified_at
                        FROM bank_card
                        WHERE user_id = ? AND status = 'ACTIVE'
                        ORDER BY created_at DESC
                        """,
                (resultSet, rowNumber) -> mapBankCard(resultSet),
                uuidToBytes(userId));
    }

    public boolean hasActiveBankCard(UUID userId) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM bank_card
                              WHERE user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, uuidToBytes(userId));
        return Boolean.TRUE.equals(active);
    }

    public void disableBankCard(UUID userId, UUID cardId) {
        int updated = jdbcTemplate.update("""
                UPDATE bank_card
                SET status = 'DISABLED', updated_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND card_id = ? AND status = 'ACTIVE'
                """, uuidToBytes(userId), uuidToBytes(cardId));
        if (updated == 0 && findBankCardRow(userId, cardId, false).isEmpty()) {
            throw new IllegalStateException("Bank card not found");
        }
    }

    public Optional<CollectionCodeRow> findCurrentCollectionCode(UUID ownerId, Instant now) {
        return jdbcTemplate.query("""
                        SELECT code_id, owner_user_id, token_nonce, token_hash,
                               status, expires_at
                        FROM collection_code
                        WHERE owner_user_id = ? AND code_type = 'PERSONAL_COLLECTION'
                          AND status = 'ACTIVE' AND expires_at > ?
                        ORDER BY expires_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapCollectionCodeRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(ownerId),
                Timestamp.from(now));
    }

    public Optional<CollectionCodeRow> findCollectionCodeByHash(byte[] tokenHash) {
        return jdbcTemplate.query("""
                        SELECT code_id, owner_user_id, token_nonce, token_hash,
                               status, expires_at
                        FROM collection_code
                        WHERE token_hash = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapCollectionCodeRow(resultSet))
                        : Optional.empty(),
                tokenHash);
    }

    public void expireCollectionCodes(UUID ownerId, Instant now) {
        jdbcTemplate.update("""
                UPDATE collection_code
                SET status = 'EXPIRED', updated_at = UTC_TIMESTAMP(6)
                WHERE owner_user_id = ? AND status = 'ACTIVE'
                  AND expires_at <= ?
                """, uuidToBytes(ownerId), Timestamp.from(now));
    }

    public void revokeCollectionCode(UUID codeId) {
        jdbcTemplate.update("""
                UPDATE collection_code
                SET status = 'REVOKED', updated_at = UTC_TIMESTAMP(6)
                WHERE code_id = ? AND status = 'ACTIVE'
                """, uuidToBytes(codeId));
    }

    public void insertCollectionCode(
            UUID codeId,
            UUID ownerId,
            String nonce,
            byte[] tokenHash,
            Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO collection_code (
                  code_id, owner_user_id, code_type, token_nonce, token_hash,
                  status, expires_at, created_at, updated_at
                ) VALUES (?, ?, 'PERSONAL_COLLECTION', ?, ?, 'ACTIVE', ?,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(codeId),
                uuidToBytes(ownerId),
                nonce,
                tokenHash,
                Timestamp.from(expiresAt));
    }

    public Optional<TransferIntentRow> findTransferIntentByIdempotency(
            String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT intent_id, intent_no, payer_user_id, payer_account_id,
                               receiver_user_id, receiver_account_id, idempotency_key,
                               request_hash, amount_cent, remark, source, status,
                               expires_at, updated_at
                        FROM transfer_intent
                        WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapTransferIntentRow(resultSet))
                        : Optional.empty(),
                idempotencyKey);
    }

    public Optional<TransferIntentRow> findTransferIntent(
            UUID payerUserId, UUID intentId) {
        return jdbcTemplate.query("""
                        SELECT intent_id, intent_no, payer_user_id, payer_account_id,
                               receiver_user_id, receiver_account_id, idempotency_key,
                               request_hash, amount_cent, remark, source, status,
                               expires_at, updated_at
                        FROM transfer_intent
                        WHERE payer_user_id = ? AND intent_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapTransferIntentRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(payerUserId),
                uuidToBytes(intentId));
    }

    public TransferIntent insertTransferIntent(
            UUID intentId,
            String intentNo,
            UUID payerUserId,
            UUID payerAccountId,
            UUID receiverUserId,
            UUID receiverAccountId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent,
            String remark,
            String source,
            Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO transfer_intent (
                  intent_id, intent_no, payer_user_id, payer_account_id,
                  receiver_user_id, receiver_account_id, idempotency_key, request_hash,
                  amount_cent, remark, source, risk_decision, status, expires_at,
                  version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PASS',
                          'PENDING_CONFIRMATION', ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(intentId),
                intentNo,
                uuidToBytes(payerUserId),
                uuidToBytes(payerAccountId),
                uuidToBytes(receiverUserId),
                uuidToBytes(receiverAccountId),
                idempotencyKey,
                requestHash,
                amountCent,
                remark,
                source,
                Timestamp.from(expiresAt));
        return findTransferIntent(payerUserId, intentId).orElseThrow().toPublicModel();
    }

    public TransferIntent cancelTransferIntent(UUID payerUserId, UUID intentId) {
        jdbcTemplate.update("""
                UPDATE transfer_intent
                SET status = CASE
                      WHEN status = 'PENDING_CONFIRMATION' THEN 'CANCELLED'
                      ELSE status
                    END,
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE payer_user_id = ? AND intent_id = ?
                """, uuidToBytes(payerUserId), uuidToBytes(intentId));
        return findTransferIntent(payerUserId, intentId)
                .orElseThrow()
                .toPublicModel();
    }

    public void confirmTransferIntent(UUID intentId) {
        int updated = jdbcTemplate.update("""
                UPDATE transfer_intent
                SET status = 'CONFIRMED', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE intent_id = ? AND status = 'PENDING_CONFIRMATION'
                  AND expires_at > UTC_TIMESTAMP(6)
                """, uuidToBytes(intentId));
        if (updated == 0) {
            throw new IllegalStateException("Transfer intent cannot be confirmed");
        }
    }

    public void expireTransferIntent(UUID intentId) {
        jdbcTemplate.update("""
                UPDATE transfer_intent
                SET status = 'EXPIRED', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE intent_id = ? AND status = 'PENDING_CONFIRMATION'
                  AND expires_at <= UTC_TIMESTAMP(6)
                """, uuidToBytes(intentId));
    }

    public Optional<TransferOrderRow> findTransferOrderByIntent(UUID intentId) {
        return jdbcTemplate.query("""
                        SELECT o.transfer_id, o.transfer_no, o.client_request_id,
                               o.intent_id, o.authorization_id, o.authorized_at,
                               o.payer_account_id, o.receiver_account_id, o.amount_cent,
                               o.status, o.xid, o.failure_code, o.recovery_error_code,
                               o.updated_at,
                               i.payer_user_id, i.receiver_user_id
                        FROM transfer_order o
                        JOIN transfer_intent i ON i.intent_id = o.intent_id
                        WHERE o.intent_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapTransferOrderRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(intentId));
    }

    public Optional<TransferOrder> findTransferOrder(UUID payerUserId, UUID transferId) {
        return jdbcTemplate.query("""
                        SELECT o.transfer_id, o.intent_id, i.receiver_user_id, o.amount_cent,
                               o.status, o.failure_code, o.updated_at
                        FROM transfer_order o
                        JOIN transfer_intent i ON i.intent_id = o.intent_id
                        WHERE i.payer_user_id = ? AND o.transfer_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new TransferOrder(
                        bytesToUuid(resultSet.getBytes("transfer_id")),
                        bytesToUuid(resultSet.getBytes("intent_id")),
                        bytesToUuid(resultSet.getBytes("receiver_user_id")),
                        resultSet.getLong("amount_cent"),
                        resultSet.getString("status"),
                        resultSet.getString("failure_code"),
                        resultSet.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                uuidToBytes(payerUserId),
                uuidToBytes(transferId));
    }

    public TransferOrderRow insertTransferOrder(
            UUID transferId,
            String transferNo,
            String clientRequestId,
            TransferIntentRow intent,
            String annualLimitMode) {
        jdbcTemplate.update("""
                INSERT INTO transfer_order (
                  transfer_id, transfer_no, client_request_id, intent_id,
                  payer_account_id, receiver_account_id, amount_cent,
                  annual_limit_mode, status, xid, version, next_recovery_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', NULL, 0,
                          DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 SECOND),
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(transferId),
                transferNo,
                clientRequestId,
                uuidToBytes(intent.intentId()),
                uuidToBytes(intent.payerAccountId()),
                uuidToBytes(intent.receiverAccountId()),
                intent.amountCent(),
                annualLimitMode);
        return findTransferOrderByIntent(intent.intentId()).orElseThrow();
    }

    public void authorizeTransfer(UUID transferId, UUID authorizationId) {
        int updated = jdbcTemplate.update("""
                UPDATE transfer_order
                SET authorization_id = ?, authorized_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                  AND authorization_id IS NULL
                """, uuidToBytes(authorizationId), uuidToBytes(transferId));
        if (updated == 0) {
            throw new IllegalStateException("Transfer order could not be authorized");
        }
    }

    public void setTransferAnnualLimitMode(UUID transferId, String mode) {
        requireOne(jdbcTemplate.update("""
                UPDATE transfer_order SET annual_limit_mode = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND annual_limit_mode IS NULL
                """, mode, uuidToBytes(transferId)));
    }

    public String transferAnnualLimitMode(UUID transferId) {
        return jdbcTemplate.queryForObject("""
                SELECT annual_limit_mode FROM transfer_order WHERE transfer_id = ?
                """, String.class, uuidToBytes(transferId));
    }

    public void completeTransferOrder(
            UUID transferId, String status, String failureCode) {
        int updated = jdbcTemplate.update("""
                UPDATE transfer_order
                SET status = ?, failure_code = ?, version = version + 1,
                    recovery_error_code = NULL,
                    execution_lease_until = NULL, next_recovery_at = NULL,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                """, status, failureCode, uuidToBytes(transferId));
        if (updated == 0
                && findTransferOrderByTransferId(transferId)
                .filter(row -> row.status().equals(status)).isEmpty()) {
            throw new IllegalStateException("Transfer order could not reach terminal state");
        }
    }

    public void bindTransferXid(UUID transferId, String xid) {
        int updated = jdbcTemplate.update("""
                UPDATE transfer_order
                SET xid = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                  AND (xid IS NULL OR xid = ?)
                """, xid, uuidToBytes(transferId), xid);
        if (updated == 0
                && findTransferOrderByTransferId(transferId)
                .filter(row -> xid.equals(row.xid())).isEmpty()) {
            throw new IllegalStateException("Transfer order is bound to another Seata XID");
        }
    }

    public boolean claimTransferExecution(UUID transferId, int leaseSeconds) {
        return jdbcTemplate.update("""
                UPDATE transfer_order
                SET execution_lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? SECOND),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                  AND (execution_lease_until IS NULL
                       OR execution_lease_until < UTC_TIMESTAMP(6))
                """, leaseSeconds, uuidToBytes(transferId)) == 1;
    }

    public void scheduleTransferRecovery(UUID transferId, String failureHint) {
        jdbcTemplate.update("""
                UPDATE transfer_order
                SET recovery_error_code = COALESCE(?, recovery_error_code),
                    recovery_attempts = recovery_attempts + 1,
                    next_recovery_at = DATE_ADD(
                      UTC_TIMESTAMP(6),
                      INTERVAL LEAST(60, POW(2, LEAST(recovery_attempts, 5))) SECOND),
                    execution_lease_until = NULL,
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                """, failureHint, uuidToBytes(transferId));
    }

    public void releaseTransferExecution(UUID transferId) {
        jdbcTemplate.update("""
                UPDATE transfer_order
                SET execution_lease_until = NULL,
                    next_recovery_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 SECOND),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE transfer_id = ? AND status = 'PROCESSING'
                """, uuidToBytes(transferId));
    }

    public Optional<TransferOrderRow> findTransferOrderByTransferId(UUID transferId) {
        return jdbcTemplate.query("""
                        SELECT o.transfer_id, o.transfer_no, o.client_request_id,
                               o.intent_id, o.authorization_id, o.authorized_at,
                               o.payer_account_id, o.receiver_account_id, o.amount_cent,
                               o.status, o.xid, o.failure_code, o.recovery_error_code,
                               o.updated_at,
                               i.payer_user_id, i.receiver_user_id
                        FROM transfer_order o
                        JOIN transfer_intent i ON i.intent_id = o.intent_id
                        WHERE o.transfer_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapTransferOrderRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(transferId));
    }

    public List<UUID> findRecoverableTransferIds(int limit) {
        return jdbcTemplate.query("""
                        SELECT transfer_id
                        FROM transfer_order
                        WHERE status = 'PROCESSING' AND authorization_id IS NOT NULL
                          AND (next_recovery_at IS NULL
                               OR next_recovery_at <= UTC_TIMESTAMP(6))
                          AND (execution_lease_until IS NULL
                               OR execution_lease_until < UTC_TIMESTAMP(6))
                        ORDER BY COALESCE(next_recovery_at, updated_at)
                        LIMIT ?
                        """,
                (resultSet, rowNumber) ->
                        bytesToUuid(resultSet.getBytes("transfer_id")),
                limit);
    }

    public Optional<RechargeRow> findRechargeByIdempotency(String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT recharge_id, recharge_no, user_id, bank_card_id, idempotency_key,
                               request_hash, amount_cent, channel, status, failure_code, updated_at
                        FROM recharge_order
                        WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapRechargeRow(resultSet))
                        : Optional.empty(),
                idempotencyKey);
    }

    public Optional<RechargeOrder> findRecharge(UUID userId, UUID rechargeId) {
        return jdbcTemplate.query("""
                        SELECT recharge_id, recharge_no, bank_card_id, amount_cent, channel,
                               status, failure_code, updated_at
                        FROM recharge_order
                        WHERE user_id = ? AND recharge_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapRecharge(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                uuidToBytes(rechargeId));
    }

    public FundingOrderPage listRecharges(UUID userId, int page, int size) {
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recharge_order WHERE user_id = ?", Long.class,
                uuidToBytes(userId));
        List<FundingOrderListItem> items = jdbcTemplate.query("""
                        SELECT r.recharge_id AS application_id, r.recharge_no AS business_no,
                               r.bank_card_id, c.bank_name, c.masked_card_no, r.amount_cent,
                               r.status, r.failure_code, r.created_at, r.updated_at
                        FROM recharge_order r
                        JOIN bank_card c ON c.card_id = r.bank_card_id AND c.user_id = r.user_id
                        WHERE r.user_id = ?
                        ORDER BY r.created_at DESC, r.recharge_id DESC
                        LIMIT ? OFFSET ?
                        """, FUNDING_ORDER_MAPPER, uuidToBytes(userId), size, (page - 1) * size);
        return new FundingOrderPage(items, page, size, total);
    }

    public Optional<RechargeRow> findRechargeRow(UUID userId, UUID rechargeId) {
        return jdbcTemplate.query("""
                        SELECT recharge_id, recharge_no, user_id, bank_card_id, idempotency_key,
                               request_hash, amount_cent, channel, status, failure_code, updated_at
                        FROM recharge_order
                        WHERE user_id = ? AND recharge_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapRechargeRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId), uuidToBytes(rechargeId));
    }

    public RechargeOrder insertRecharge(
            UUID rechargeId,
            String rechargeNo,
            UUID userId,
            UUID bankCardId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent) {
        jdbcTemplate.update("""
                INSERT INTO recharge_order (
                  recharge_id, recharge_no, user_id, bank_card_id, idempotency_key,
                  request_hash, amount_cent, channel, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'BANK_CARD', 'PROCESSING', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(rechargeId),
                rechargeNo,
                uuidToBytes(userId),
                uuidToBytes(bankCardId),
                idempotencyKey,
                requestHash,
                amountCent);
        return findRecharge(userId, rechargeId).orElseThrow();
    }

    public RechargeOrder insertRechargeIntent(
            UUID rechargeId,
            String rechargeNo,
            UUID userId,
            UUID bankCardId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent) {
        jdbcTemplate.update("""
                INSERT INTO recharge_order (
                  recharge_id, recharge_no, user_id, bank_card_id, idempotency_key,
                  request_hash, amount_cent, channel, status, confirmation_expires_at,
                  version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'BANK_CARD', 'PENDING_CONFIRMATION',
                          DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE), 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(rechargeId), rechargeNo, uuidToBytes(userId), uuidToBytes(bankCardId),
                idempotencyKey, requestHash, amountCent);
        return findRecharge(userId, rechargeId).orElseThrow();
    }

    public boolean authorizeRecharge(UUID rechargeId, UUID authorizationId) {
        return jdbcTemplate.update("""
                UPDATE recharge_order
                SET authorization_id = ?, authorized_at = UTC_TIMESTAMP(6),
                    status = 'PROCESSING', version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE recharge_id = ? AND status = 'PENDING_CONFIRMATION'
                  AND confirmation_expires_at > UTC_TIMESTAMP(6)
                """, uuidToBytes(authorizationId), uuidToBytes(rechargeId)) == 1;
    }

    public void closeExpiredRechargeIntent(UUID rechargeId) {
        jdbcTemplate.update("""
                UPDATE recharge_order
                SET status = 'CLOSED', failure_code = 'CONFIRMATION_EXPIRED',
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE recharge_id = ? AND status = 'PENDING_CONFIRMATION'
                  AND confirmation_expires_at <= UTC_TIMESTAMP(6)
                """, uuidToBytes(rechargeId));
    }

    public void completeRecharge(UUID rechargeId, String status, String failureCode) {
        requireOne(jdbcTemplate.update("""
                UPDATE recharge_order
                SET status = ?, failure_code = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE recharge_id = ? AND status = 'PROCESSING'
                """, status, failureCode, uuidToBytes(rechargeId)));
    }

    public Optional<WithdrawalRow> findWithdrawalByIdempotency(String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT withdrawal_id, withdrawal_no, user_id, bank_card_id,
                               authorization_id, authorized_at, idempotency_key,
                               request_hash, amount_cent, status,
                               bank_request_no, failure_code, updated_at
                        FROM withdrawal_order
                        WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapWithdrawalRow(resultSet))
                        : Optional.empty(),
                idempotencyKey);
    }

    public Optional<WithdrawalOrder> findWithdrawal(UUID userId, UUID withdrawalId) {
        return jdbcTemplate.query("""
                        SELECT withdrawal_id, withdrawal_no, bank_card_id, amount_cent,
                               status, failure_code, updated_at
                        FROM withdrawal_order
                        WHERE user_id = ? AND withdrawal_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapWithdrawal(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                uuidToBytes(withdrawalId));
    }

    public FundingOrderPage listWithdrawals(UUID userId, int page, int size) {
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM withdrawal_order WHERE user_id = ?", Long.class,
                uuidToBytes(userId));
        List<FundingOrderListItem> items = jdbcTemplate.query("""
                        SELECT w.withdrawal_id AS application_id, w.withdrawal_no AS business_no,
                               w.bank_card_id, c.bank_name, c.masked_card_no, w.amount_cent,
                               w.status, w.failure_code, w.created_at, w.updated_at
                        FROM withdrawal_order w
                        JOIN bank_card c ON c.card_id = w.bank_card_id AND c.user_id = w.user_id
                        WHERE w.user_id = ?
                        ORDER BY w.created_at DESC, w.withdrawal_id DESC
                        LIMIT ? OFFSET ?
                        """, FUNDING_ORDER_MAPPER, uuidToBytes(userId), size, (page - 1) * size);
        return new FundingOrderPage(items, page, size, total);
    }

    public Optional<WithdrawalRow> findWithdrawalByIdempotencyForOrder(
            UUID userId, UUID withdrawalId) {
        return jdbcTemplate.query("""
                        SELECT withdrawal_id, withdrawal_no, user_id, bank_card_id,
                               authorization_id, authorized_at, idempotency_key,
                               request_hash, amount_cent, status,
                               bank_request_no, failure_code, updated_at
                        FROM withdrawal_order
                        WHERE user_id = ? AND withdrawal_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapWithdrawalRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                uuidToBytes(withdrawalId));
    }

    public WithdrawalOrder insertWithdrawal(
            UUID withdrawalId,
            String withdrawalNo,
            UUID userId,
            UUID bankCardId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent) {
        jdbcTemplate.update("""
                INSERT INTO withdrawal_order (
                  withdrawal_id, withdrawal_no, user_id, bank_card_id, idempotency_key,
                  request_hash, amount_cent, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(withdrawalId),
                withdrawalNo,
                uuidToBytes(userId),
                uuidToBytes(bankCardId),
                idempotencyKey,
                requestHash,
                amountCent);
        return findWithdrawal(userId, withdrawalId).orElseThrow();
    }

    public void completeWithdrawal(
            UUID withdrawalId,
            String status,
            String bankRequestNo,
            String failureCode) {
        requireOne(jdbcTemplate.update("""
                UPDATE withdrawal_order
                SET status = ?, bank_request_no = ?, failure_code = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE withdrawal_id = ? AND status = 'PROCESSING'
                """, status, bankRequestNo, failureCode, uuidToBytes(withdrawalId)));
    }

    public void authorizeWithdrawal(UUID withdrawalId, UUID authorizationId) {
        int updated = jdbcTemplate.update("""
                UPDATE withdrawal_order
                SET authorization_id = ?, authorized_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE withdrawal_id = ? AND status = 'PROCESSING'
                  AND authorization_id IS NULL
                """, uuidToBytes(authorizationId), uuidToBytes(withdrawalId));
        if (updated == 0) {
            WithdrawalRow row = jdbcTemplate.query("""
                            SELECT withdrawal_id, withdrawal_no, user_id, bank_card_id,
                                   authorization_id, authorized_at, idempotency_key,
                                   request_hash, amount_cent, status, bank_request_no,
                                   failure_code, updated_at
                            FROM withdrawal_order
                            WHERE withdrawal_id = ?
                            """,
                    resultSet -> resultSet.next() ? mapWithdrawalRow(resultSet) : null,
                    uuidToBytes(withdrawalId));
            if (row == null || row.authorizationId() == null) {
                throw new IllegalStateException("Withdrawal could not be authorized");
            }
        }
    }

    public Optional<PaymentOrderRow> findPaymentByIdempotency(
            UUID userId, String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id,
                               p.authorization_id, p.authorized_at,
                               p.client_idempotency_key, p.merchant_order_no,
                               p.merchant_id, p.application_id, p.app_id, p.resolution_id,
                               p.amount_cent, p.currency, p.subject,
                               p.channel, p.status, p.expires_at, p.updated_at,
                               a.redirect_url, a.failure_code
                        FROM payment_order p
                        LEFT JOIN payment_attempt a ON a.pay_order_id = p.pay_order_id
                        WHERE p.payer_user_id = ?
                          AND (p.client_idempotency_key = ?
                               OR (p.client_idempotency_key IS NULL
                                   AND p.app_id = 'consumer-sandbox' AND p.merchant_order_no = ?))
                        ORDER BY a.created_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapPaymentOrderRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                idempotencyKey,
                idempotencyKey);
    }

    public Optional<PaymentOrderRow> findPayment(UUID userId, UUID paymentOrderId) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id,
                               p.authorization_id, p.authorized_at,
                               p.client_idempotency_key, p.merchant_order_no,
                               p.merchant_id, p.application_id, p.app_id, p.resolution_id,
                               p.amount_cent, p.currency, p.subject,
                               p.channel, p.status, p.expires_at, p.updated_at,
                               a.redirect_url, a.failure_code
                        FROM payment_order p
                        LEFT JOIN payment_attempt a ON a.pay_order_id = p.pay_order_id
                        WHERE p.payer_user_id = ? AND p.pay_order_id = ?
                        ORDER BY a.created_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapPaymentOrderRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(userId),
                uuidToBytes(paymentOrderId));
    }

    public List<PaymentOrderRow> findExpiredProcessingPayments(Instant now, int limit) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id,
                               p.authorization_id, p.authorized_at,
                               p.client_idempotency_key, p.merchant_order_no,
                               p.merchant_id, p.application_id, p.app_id, p.resolution_id,
                               p.amount_cent, p.currency, p.subject,
                               p.channel, p.status, p.expires_at, p.updated_at,
                               NULL AS redirect_url, NULL AS failure_code
                        FROM payment_order p
                        WHERE p.status = 'PROCESSING' AND p.expires_at <= ?
                        ORDER BY p.expires_at, p.pay_order_id
                        LIMIT ?
                        """,
                (resultSet, ignored) -> mapPaymentOrderRow(resultSet),
                java.sql.Timestamp.from(now), Math.min(Math.max(limit, 1), 200));
    }

    public Optional<PaymentOrderRow> findPaymentByMerchantOrder(
            String appId, String merchantOrderNo) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id,
                               p.authorization_id, p.authorized_at,
                               p.client_idempotency_key, p.merchant_order_no,
                               p.merchant_id, p.application_id, p.app_id, p.resolution_id,
                               p.amount_cent, p.currency, p.subject,
                               p.channel, p.status, p.expires_at, p.updated_at,
                               a.redirect_url, a.failure_code
                        FROM payment_order p
                        LEFT JOIN payment_attempt a ON a.pay_order_id = p.pay_order_id
                        WHERE p.app_id = ? AND p.merchant_order_no = ?
                        ORDER BY a.created_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapPaymentOrderRow(resultSet))
                        : Optional.empty(),
                appId, merchantOrderNo);
    }

    public Optional<PaymentOrderRow> findPaymentByNo(String appId, String paymentOrderNo) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id,
                               p.authorization_id, p.authorized_at,
                               p.client_idempotency_key, p.merchant_order_no,
                               p.merchant_id, p.application_id, p.app_id, p.resolution_id,
                               p.amount_cent, p.currency, p.subject,
                               p.channel, p.status, p.expires_at, p.updated_at,
                               a.redirect_url, a.failure_code
                        FROM payment_order p
                        LEFT JOIN payment_attempt a ON a.pay_order_id = p.pay_order_id
                        WHERE p.app_id = ? AND p.pay_order_no = ?
                        ORDER BY a.created_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapPaymentOrderRow(resultSet))
                        : Optional.empty(),
                appId, paymentOrderNo);
    }

    public Optional<RefundablePaymentRow> findRefundablePayment(UUID paymentOrderId) {
        return jdbcTemplate.query("""
                        SELECT p.pay_order_id, p.pay_order_no, p.payer_user_id, p.amount_cent,
                               p.currency, p.channel, p.status, p.merchant_id, p.application_id,
                               p.app_id, p.merchant_order_no, m.owner_user_id AS merchant_owner_user_id,
                               m.name AS merchant_name
                        FROM payment_order p
                        LEFT JOIN merchant m ON m.merchant_id = p.merchant_id
                        WHERE p.pay_order_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new RefundablePaymentRow(
                        bytesToUuid(resultSet.getBytes("pay_order_id")),
                        resultSet.getString("pay_order_no"),
                        bytesToUuid(resultSet.getBytes("payer_user_id")),
                        resultSet.getLong("amount_cent"),
                        resultSet.getString("currency"),
                        resultSet.getString("channel"),
                        resultSet.getString("status"),
                        bytesToUuid(resultSet.getBytes("merchant_id")),
                        bytesToUuid(resultSet.getBytes("application_id")),
                        resultSet.getString("app_id"),
                        resultSet.getString("merchant_order_no"),
                        bytesToUuid(resultSet.getBytes("merchant_owner_user_id")),
                        resultSet.getString("merchant_name")))
                        : Optional.empty(),
                uuidToBytes(paymentOrderId));
    }

    public Optional<RefundRow> findRefundByRequestNo(String merchantRefundNo) {
        return jdbcTemplate.query("""
                        SELECT refund_order_id, refund_order_no, pay_order_id,
                               merchant_refund_no, amount_cent, status,
                               failure_code, updated_at
                        FROM refund_order
                        WHERE merchant_refund_no = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapRefundRow(resultSet))
                        : Optional.empty(),
                merchantRefundNo);
    }

    public Optional<RefundRow> findRefundByPayment(UUID paymentOrderId) {
        return jdbcTemplate.query("""
                        SELECT refund_order_id, refund_order_no, pay_order_id,
                               merchant_refund_no, amount_cent, status,
                               failure_code, updated_at
                        FROM refund_order
                        WHERE pay_order_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapRefundRow(resultSet))
                        : Optional.empty(),
                uuidToBytes(paymentOrderId));
    }

    public RefundRow insertRefund(
            UUID refundId,
            String refundNo,
            UUID paymentOrderId,
            String merchantRefundNo,
            long amountCent,
            String reason) {
        jdbcTemplate.update("""
                INSERT INTO refund_order (
                  refund_order_id, refund_order_no, pay_order_id,
                  merchant_refund_no, amount_cent, reason, status,
                  version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(refundId),
                refundNo,
                uuidToBytes(paymentOrderId),
                merchantRefundNo,
                amountCent,
                reason);
        return findRefundByRequestNo(merchantRefundNo).orElseThrow();
    }

    public void completeRefund(UUID refundId, String status, String failureCode) {
        int updated = jdbcTemplate.update("""
                UPDATE refund_order
                SET status = ?, failure_code = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE refund_order_id = ? AND status = 'PROCESSING'
                """, status, failureCode, uuidToBytes(refundId));
        if (updated == 0) {
            throw new IllegalStateException("Refund order could not reach terminal state");
        }
    }

    public PaymentOrder insertPaymentOrder(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID attemptId,
            UUID userId,
            String idempotencyKey,
            long amountCent,
            String subject,
            String method,
            String channelRequestNo,
            String redirectUrl,
            Instant expiresAt,
            String annualLimitMode) {
        return insertPaymentOrder(paymentOrderId, paymentOrderNo, attemptId, userId,
                idempotencyKey, amountCent, subject, method, channelRequestNo, redirectUrl,
                expiresAt, annualLimitMode, null, null, "consumer-sandbox", idempotencyKey, null);
    }

    public PaymentOrder insertPaymentOrder(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID attemptId,
            UUID userId,
            String idempotencyKey,
            long amountCent,
            String subject,
            String method,
            String channelRequestNo,
            String redirectUrl,
            Instant expiresAt,
            String annualLimitMode,
            UUID merchantId,
            UUID applicationId,
            String appId,
            String merchantOrderNo,
            UUID resolutionId) {
        jdbcTemplate.update("""
                INSERT INTO payment_order (
                  pay_order_id, pay_order_no, merchant_id, application_id, resolution_id,
                  client_idempotency_key, app_id, merchant_order_no, payer_user_id,
                  amount_cent, currency, subject, channel, status, expires_at,
                  annual_limit_mode, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, 'PROCESSING', ?, ?,
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(paymentOrderId),
                paymentOrderNo,
                merchantId == null ? null : uuidToBytes(merchantId),
                applicationId == null ? null : uuidToBytes(applicationId),
                resolutionId == null ? null : uuidToBytes(resolutionId),
                idempotencyKey,
                appId,
                merchantOrderNo,
                uuidToBytes(userId),
                amountCent,
                subject,
                method,
                Timestamp.from(expiresAt),
                annualLimitMode);
        jdbcTemplate.update("""
                INSERT INTO payment_attempt (
                  attempt_id, pay_order_id, payment_method, channel_request_no,
                  status, redirect_url, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PROCESSING', ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(attemptId),
                uuidToBytes(paymentOrderId),
                method,
                channelRequestNo,
                redirectUrl);
        return findPaymentByIdempotency(userId, idempotencyKey)
                .orElseThrow()
                .toPublicModel();
    }

    public void completePaymentOrder(
            UUID paymentOrderId,
            String status,
            String channelTransactionNo,
            String failureCode) {
        requireOne(jdbcTemplate.update("""
                UPDATE payment_order
                SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE pay_order_id = ? AND status = 'PROCESSING'
                """, status, uuidToBytes(paymentOrderId)));
        requireOne(jdbcTemplate.update("""
                UPDATE payment_attempt
                SET status = ?, channel_transaction_no = ?, failure_code = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE pay_order_id = ? AND status = 'PROCESSING'
                """,
                status,
                channelTransactionNo,
                failureCode,
                uuidToBytes(paymentOrderId)));
    }

    public void authorizePayment(UUID paymentOrderId, UUID authorizationId) {
        int updated = jdbcTemplate.update("""
                UPDATE payment_order
                SET authorization_id = ?, authorized_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE pay_order_id = ? AND status = 'PROCESSING'
                  AND authorization_id IS NULL
                """, uuidToBytes(authorizationId), uuidToBytes(paymentOrderId));
        if (updated == 0) {
            throw new IllegalStateException("Payment order could not be authorized");
        }
    }

    public Optional<UUID> findPaymentOwner(UUID paymentOrderId) {
        return jdbcTemplate.query("""
                SELECT payer_user_id FROM payment_order WHERE pay_order_id = ?
                """, rs -> rs.next()
                ? Optional.of(bytesToUuid(rs.getBytes("payer_user_id")))
                : Optional.empty(), uuidToBytes(paymentOrderId));
    }

    public void linkFoodPayment(
            UUID foodOrderId,
            String foodOrderNo,
            UUID paymentOrderId,
            UUID payerUserId,
            long amountCent,
            UUID createdEventId) {
        jdbcTemplate.update("""
                INSERT INTO food_order_payment_reference (
                  food_order_id, food_order_no, pay_order_id, payer_user_id,
                  amount_cent, currency, created_event_id, created_at
                ) VALUES (?, ?, ?, ?, ?, 'CNY', ?, UTC_TIMESTAMP(6))
                """, uuidToBytes(foodOrderId), foodOrderNo, uuidToBytes(paymentOrderId),
                uuidToBytes(payerUserId), amountCent, uuidToBytes(createdEventId));
    }

    public Optional<UUID> findFoodOrderId(UUID paymentOrderId) {
        return jdbcTemplate.query("""
                SELECT food_order_id
                FROM food_order_payment_reference
                WHERE pay_order_id = ?
                """, rs -> rs.next()
                ? Optional.of(bytesToUuid(rs.getBytes("food_order_id")))
                : Optional.empty(), uuidToBytes(paymentOrderId));
    }

    public Optional<PaymentOrderRow> findFoodPayment(UUID userId, UUID foodOrderId) {
        UUID paymentOrderId = jdbcTemplate.query("""
                SELECT pay_order_id
                FROM food_order_payment_reference
                WHERE payer_user_id = ? AND food_order_id = ?
                """, rs -> rs.next() ? bytesToUuid(rs.getBytes("pay_order_id")) : null,
                uuidToBytes(userId), uuidToBytes(foodOrderId));
        return paymentOrderId == null ? Optional.empty() : findPayment(userId, paymentOrderId);
    }

    public boolean claimInbox(UUID eventId, String consumerName, String eventType) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO inbox_message (
                      event_id, consumer_name, event_type, received_at, processed_at
                    ) VALUES (?, ?, ?, UTC_TIMESTAMP(6), NULL)
                    """, uuidToBytes(eventId), consumerName, eventType) == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            Boolean pending = jdbcTemplate.query("""
                    SELECT processed_at IS NULL AS pending
                    FROM inbox_message
                    WHERE event_id = ? AND consumer_name = ?
                    """, rs -> rs.next() && rs.getBoolean("pending"),
                    uuidToBytes(eventId), consumerName);
            return Boolean.TRUE.equals(pending);
        }
    }

    public void completeInbox(UUID eventId, String consumerName) {
        jdbcTemplate.update("""
                UPDATE inbox_message
                SET processed_at = COALESCE(processed_at, UTC_TIMESTAMP(6))
                WHERE event_id = ? AND consumer_name = ?
                """, uuidToBytes(eventId), consumerName);
    }

    public void setPaymentAnnualLimitMode(UUID paymentOrderId, String mode) {
        int updated = jdbcTemplate.update("""
                UPDATE payment_order SET annual_limit_mode = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE pay_order_id = ? AND annual_limit_mode IS NULL
                """, mode, uuidToBytes(paymentOrderId));
        if (updated == 0 && paymentAnnualLimitMode(paymentOrderId) == null) {
            throw new IllegalStateException("Payment annual limit mode was not persisted");
        }
    }

    public String paymentAnnualLimitMode(UUID paymentOrderId) {
        return jdbcTemplate.queryForObject("""
                SELECT annual_limit_mode FROM payment_order WHERE pay_order_id = ?
                """, String.class, uuidToBytes(paymentOrderId));
    }

    public void insertOutbox(
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            String payload) {
        jdbcTemplate.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  payload_version, payload, status, attempts, next_attempt_at, created_at
                ) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), 1, CAST(? AS JSON),
                          'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                uuidToBytes(eventId),
                eventType,
                aggregateType,
                uuidToBytes(aggregateId),
                payload);
    }

    private static BankCard mapBankCard(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new BankCard(
                bytesToUuid(resultSet.getBytes("card_id")),
                resultSet.getString("bank_name"),
                resultSet.getString("card_type"),
                resultSet.getString("masked_card_no"),
                resultSet.getString("status"),
                resultSet.getTimestamp("verified_at").toInstant());
    }

    private static RechargeRow mapRechargeRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new RechargeRow(
                bytesToUuid(resultSet.getBytes("recharge_id")),
                resultSet.getString("recharge_no"),
                bytesToUuid(resultSet.getBytes("user_id")),
                bytesToUuid(resultSet.getBytes("bank_card_id")),
                resultSet.getString("idempotency_key"),
                resultSet.getBytes("request_hash"),
                resultSet.getLong("amount_cent"),
                resultSet.getString("channel"),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static final RowMapper<FundingOrderListItem> FUNDING_ORDER_MAPPER = (rs, row) ->
            new FundingOrderListItem(
                    bytesToUuid(rs.getBytes("application_id")),
                    rs.getString("business_no"),
                    bytesToUuid(rs.getBytes("bank_card_id")),
                    rs.getString("bank_name"),
                    rs.getString("masked_card_no"),
                    rs.getLong("amount_cent"),
                    rs.getString("status"),
                    rs.getString("failure_code"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());

    private static TransferIntentRow mapTransferIntentRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new TransferIntentRow(
                bytesToUuid(resultSet.getBytes("intent_id")),
                resultSet.getString("intent_no"),
                bytesToUuid(resultSet.getBytes("payer_user_id")),
                bytesToUuid(resultSet.getBytes("payer_account_id")),
                bytesToUuid(resultSet.getBytes("receiver_user_id")),
                bytesToUuid(resultSet.getBytes("receiver_account_id")),
                resultSet.getString("idempotency_key"),
                resultSet.getBytes("request_hash"),
                resultSet.getLong("amount_cent"),
                resultSet.getString("remark"),
                resultSet.getString("source"),
                resultSet.getString("status"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static TransferOrderRow mapTransferOrderRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new TransferOrderRow(
                bytesToUuid(resultSet.getBytes("transfer_id")),
                resultSet.getString("transfer_no"),
                resultSet.getString("client_request_id"),
                bytesToUuid(resultSet.getBytes("intent_id")),
                bytesToUuid(resultSet.getBytes("authorization_id")),
                resultSet.getTimestamp("authorized_at") == null
                        ? null : resultSet.getTimestamp("authorized_at").toInstant(),
                bytesToUuid(resultSet.getBytes("payer_user_id")),
                bytesToUuid(resultSet.getBytes("payer_account_id")),
                bytesToUuid(resultSet.getBytes("receiver_user_id")),
                bytesToUuid(resultSet.getBytes("receiver_account_id")),
                resultSet.getLong("amount_cent"),
                resultSet.getString("status"),
                resultSet.getString("xid"),
                resultSet.getString("failure_code"),
                resultSet.getString("recovery_error_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static RechargeOrder mapRecharge(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new RechargeOrder(
                bytesToUuid(resultSet.getBytes("recharge_id")),
                resultSet.getString("recharge_no"),
                bytesToUuid(resultSet.getBytes("bank_card_id")),
                resultSet.getLong("amount_cent"),
                resultSet.getString("channel"),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static WithdrawalRow mapWithdrawalRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new WithdrawalRow(
                bytesToUuid(resultSet.getBytes("withdrawal_id")),
                resultSet.getString("withdrawal_no"),
                bytesToUuid(resultSet.getBytes("user_id")),
                bytesToUuid(resultSet.getBytes("bank_card_id")),
                bytesToUuid(resultSet.getBytes("authorization_id")),
                resultSet.getTimestamp("authorized_at") == null
                        ? null : resultSet.getTimestamp("authorized_at").toInstant(),
                resultSet.getString("idempotency_key"),
                resultSet.getBytes("request_hash"),
                resultSet.getLong("amount_cent"),
                resultSet.getString("status"),
                resultSet.getString("bank_request_no"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static CollectionCodeRow mapCollectionCodeRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new CollectionCodeRow(
                bytesToUuid(resultSet.getBytes("code_id")),
                bytesToUuid(resultSet.getBytes("owner_user_id")),
                resultSet.getString("token_nonce"),
                resultSet.getBytes("token_hash"),
                resultSet.getString("status"),
                resultSet.getTimestamp("expires_at").toInstant());
    }

    private static WithdrawalOrder mapWithdrawal(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new WithdrawalOrder(
                bytesToUuid(resultSet.getBytes("withdrawal_id")),
                resultSet.getString("withdrawal_no"),
                bytesToUuid(resultSet.getBytes("bank_card_id")),
                resultSet.getLong("amount_cent"),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static PaymentOrderRow mapPaymentOrderRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new PaymentOrderRow(
                bytesToUuid(resultSet.getBytes("pay_order_id")),
                resultSet.getString("pay_order_no"),
                bytesToUuid(resultSet.getBytes("payer_user_id")),
                bytesToUuid(resultSet.getBytes("authorization_id")),
                resultSet.getTimestamp("authorized_at") == null
                        ? null : resultSet.getTimestamp("authorized_at").toInstant(),
                resultSet.getString("client_idempotency_key") == null
                        ? resultSet.getString("merchant_order_no")
                        : resultSet.getString("client_idempotency_key"),
                resultSet.getLong("amount_cent"),
                resultSet.getString("currency"),
                resultSet.getString("subject"),
                resultSet.getString("channel"),
                resultSet.getString("status"),
                resultSet.getString("redirect_url"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                bytesToUuid(resultSet.getBytes("merchant_id")),
                bytesToUuid(resultSet.getBytes("application_id")),
                resultSet.getString("app_id"),
                resultSet.getString("merchant_order_no"),
                bytesToUuid(resultSet.getBytes("resolution_id")));
    }

    private static RefundRow mapRefundRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new RefundRow(
                bytesToUuid(resultSet.getBytes("refund_order_id")),
                resultSet.getString("refund_order_no"),
                bytesToUuid(resultSet.getBytes("pay_order_id")),
                resultSet.getString("merchant_refund_no"),
                resultSet.getLong("amount_cent"),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new IllegalStateException("Expected one payment row to change, got " + count);
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

    public record BankCardRow(
            UUID cardId,
            UUID userId,
            String provider,
            String providerToken,
            String bankName,
            String cardType,
            String maskedCardNo,
            String lastFour,
            String holderName,
            String status,
            Instant verifiedAt) {
        public BankCard toPublicModel() {
            return new BankCard(cardId, bankName, cardType, maskedCardNo, status, verifiedAt);
        }
    }

    public record RechargeRow(
            UUID rechargeId,
            String rechargeNo,
            UUID userId,
            UUID bankCardId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent,
            String channel,
            String status,
            String failureCode,
            Instant updatedAt) {
        public RechargeOrder toPublicModel() {
            return new RechargeOrder(
                    rechargeId,
                    rechargeNo,
                    bankCardId,
                    amountCent,
                    channel,
                    status,
                    failureCode,
                    updatedAt);
        }
    }

    public record TransferIntentRow(
            UUID intentId,
            String intentNo,
            UUID payerUserId,
            UUID payerAccountId,
            UUID receiverUserId,
            UUID receiverAccountId,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent,
            String remark,
            String source,
            String status,
            Instant expiresAt,
            Instant updatedAt) {
        public TransferIntent toPublicModel() {
            return new TransferIntent(intentId, amountCent, status, expiresAt);
        }
    }

    public record TransferOrderRow(
            UUID transferId,
            String transferNo,
            String clientRequestId,
            UUID intentId,
            UUID authorizationId,
            Instant authorizedAt,
            UUID payerUserId,
            UUID payerAccountId,
            UUID receiverUserId,
            UUID receiverAccountId,
            long amountCent,
            String status,
            String xid,
            String failureCode,
            String recoveryErrorCode,
            Instant updatedAt) {
        public TransferOrder toPublicModel() {
            return new TransferOrder(
                    transferId,
                    intentId,
                    receiverUserId,
                    amountCent,
                    status,
                    failureCode,
                    updatedAt);
        }
    }

    public record CollectionCodeRow(
            UUID codeId,
            UUID ownerId,
            String nonce,
            byte[] tokenHash,
            String status,
            Instant expiresAt) {
    }

    public record WithdrawalRow(
            UUID withdrawalId,
            String withdrawalNo,
            UUID userId,
            UUID bankCardId,
            UUID authorizationId,
            Instant authorizedAt,
            String idempotencyKey,
            byte[] requestHash,
            long amountCent,
            String status,
            String bankRequestNo,
            String failureCode,
            Instant updatedAt) {
        public WithdrawalOrder toPublicModel() {
            return new WithdrawalOrder(
                    withdrawalId,
                    withdrawalNo,
                    bankCardId,
                    amountCent,
                    status,
                    failureCode,
                    updatedAt);
        }
    }

    public record PaymentOrderRow(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID userId,
            UUID authorizationId,
            Instant authorizedAt,
            String idempotencyKey,
            long amountCent,
            String currency,
            String subject,
            String paymentMethod,
            String status,
            String redirectUrl,
            String failureCode,
            Instant expiresAt,
            Instant updatedAt,
            UUID merchantId,
            UUID applicationId,
            String appId,
            String merchantOrderNo,
            UUID resolutionId) {
        public PaymentOrder toPublicModel() {
            return new PaymentOrder(
                    paymentOrderId,
                    paymentOrderNo,
                    amountCent,
                    currency,
                    subject,
                    paymentMethod,
                    status,
                    redirectUrl,
                    failureCode,
                    expiresAt,
                    updatedAt);
        }
    }

    public record RefundablePaymentRow(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID userId,
            long amountCent,
            String currency,
            String paymentMethod,
            String status,
            UUID merchantId,
            UUID applicationId,
            String appId,
            String merchantOrderNo,
            UUID merchantOwnerUserId,
            String merchantName) {
        public RefundablePaymentRow(
                UUID paymentOrderId, String paymentOrderNo, UUID userId,
                long amountCent, String currency, String paymentMethod, String status) {
            this(paymentOrderId, paymentOrderNo, userId, amountCent, currency,
                    paymentMethod, status, null, null, null, null, null, null);
        }
    }

    public record RefundRow(
            UUID refundId,
            String refundNo,
            UUID paymentOrderId,
            String merchantRefundNo,
            long amountCent,
            String status,
            String failureCode,
            Instant updatedAt) {
        public Refund toPublicModel() {
            return new Refund(
                    refundId,
                    paymentOrderId,
                    amountCent,
                    status,
                    failureCode,
                    updatedAt);
        }
    }
}
