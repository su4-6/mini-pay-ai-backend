package com.minipay.wallet.application.service;

import com.minipay.wallet.infrastructure.persistence.WalletRepository;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.AccountRow;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.BranchRow;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletTransferTccService {
    private final WalletRepository repository;
    private final com.minipay.wallet.infrastructure.persistence.AnnualOutflowRepository annualOutflow;
    private final ApplicationEventPublisher events;

    public WalletTransferTccService(
            WalletRepository repository,
            com.minipay.wallet.infrastructure.persistence.AnnualOutflowRepository annualOutflow,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.annualOutflow = annualOutflow;
        this.events = events;
    }

    @Transactional
    public boolean tryDebit(
            String xid,
            long branchId,
            String businessNo,
            String source,
            UUID ownerId,
            UUID accountId,
            UUID counterpartyOwnerId,
            long amountCent,
            String annualLimitMode) {
        if (amountCent <= 0) return false;
        AccountRow account = repository.lockAccountById(accountId).orElse(null);
        if (account == null || !account.ownerId().equals(ownerId)
                || !"ACTIVE".equals(account.status())) {
            return false;
        }
        BranchRow replay = repository.lockFreeze(xid, branchId).orElse(null);
        if (replay != null) {
            return replay.accountId().equals(accountId)
                    && replay.amountCent() == amountCent
                    && replay.businessNo().equals(businessNo)
                    && replay.annualLimitMode().equals(annualLimitMode)
                    && !"CANCELLED".equals(replay.status());
        }
        if (account.availableAmountCent() < amountCent) return false;
        if (!"LIMITED".equals(annualLimitMode)
                && !"EXEMPT_ACTIVE_CARD".equals(annualLimitMode)) return false;
        int limitYear = "LIMITED".equals(annualLimitMode)
                ? annualOutflow.reserve(ownerId, businessNo, amountCent) : 0;
        long reserved = "LIMITED".equals(annualLimitMode) ? amountCent : 0;
        if (!repository.insertFreeze(
                UuidV7.generate(), xid, branchId, businessNo, source, accountId,
                counterpartyOwnerId, amountCent,
                annualLimitMode, limitYear == 0 ? null : limitYear, reserved)) {
            throw new IllegalStateException("Concurrent debit branch creation");
        }
        repository.changeBalance(accountId, -amountCent, amountCent);
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                account.ownerId(),
                accountId,
                "TRANSFER",
                businessNo,
                "EXPENSE",
                amountCent,
                "站内收款人",
                null,
                "PROCESSING",
                null,
                null,
                source,
                counterpartyOwnerId);
        return true;
    }

    /** Backward-compatible branch adapter for already deployed callers during rolling upgrade. */
    public boolean tryDebit(
            String xid, long branchId, String businessNo, String source,
            UUID ownerId, UUID accountId, long amountCent, String annualLimitMode) {
        return tryDebit(xid, branchId, businessNo, source, ownerId, accountId,
                null, amountCent, annualLimitMode);
    }

    @Transactional
    public boolean confirmDebit(String xid, long branchId) {
        BranchRow branch = repository.lockFreeze(xid, branchId).orElse(null);
        if (branch == null) return false;
        if ("CONFIRMED".equals(branch.status())) return true;
        if (!"TRY".equals(branch.status())) return false;
        AccountRow account = repository.lockAccountById(branch.accountId()).orElseThrow();
        if (branch.reservedLimitAmountCent() > 0) {
            annualOutflow.confirmReservation(
                    account.ownerId(), branch.businessNo(), branch.limitYear(),
                    branch.reservedLimitAmountCent());
        }
        repository.changeBalance(branch.accountId(), 0, -branch.amountCent());
        repository.changeBalance(
                WalletRepository.TRANSFER_CLEARING_ACCOUNT, branch.amountCent(), 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "TRANSFER_OUT",
                branch.businessNo(),
                branch.amountCent(),
                branch.accountId(),
                WalletRepository.TRANSFER_CLEARING_ACCOUNT);
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                account.ownerId(),
                branch.accountId(),
                "TRANSFER",
                branch.businessNo(),
                "EXPENSE",
                branch.amountCent(),
                "站内收款人",
                null,
                "SUCCEEDED",
                account.availableAmountCent(),
                null,
                branch.source(),
                branch.counterpartyUserId());
        repository.updateBranchStatus("account_freeze", xid, branchId, "CONFIRMED");
        return true;
    }

    @Transactional
    public boolean cancelDebit(String xid, long branchId) {
        BranchRow branch = repository.lockFreeze(xid, branchId).orElse(null);
        if (branch == null) return true;
        if ("CANCELLED".equals(branch.status())) return true;
        if (!"TRY".equals(branch.status())) return false;
        AccountRow account = repository.lockAccountById(branch.accountId()).orElseThrow();
        if (branch.reservedLimitAmountCent() > 0) {
            annualOutflow.cancelReservation(
                    account.ownerId(), branch.businessNo(), branch.limitYear(),
                    branch.reservedLimitAmountCent());
        }
        repository.changeBalance(branch.accountId(), branch.amountCent(), -branch.amountCent());
        repository.insertBill(
                UuidV7.generate(),
                account.ownerId(),
                branch.accountId(),
                "TRANSFER",
                branch.businessNo(),
                "EXPENSE",
                branch.amountCent(),
                "站内收款人",
                null,
                "FAILED",
                account.availableAmountCent() + branch.amountCent(),
                "TRANSFER_CANCELLED",
                branch.source(),
                branch.counterpartyUserId());
        repository.updateBranchStatus("account_freeze", xid, branchId, "CANCELLED");
        return true;
    }

    @Transactional
    public boolean tryCredit(
            String xid,
            long branchId,
            String businessNo,
            String source,
            UUID ownerId,
            UUID accountId,
            UUID counterpartyOwnerId,
            long amountCent) {
        if (amountCent <= 0) return false;
        AccountRow account = repository.lockAccountById(accountId).orElse(null);
        if (account == null || !account.ownerId().equals(ownerId)
                || !"ACTIVE".equals(account.status())) return false;
        if (!repository.insertPendingCredit(
                UuidV7.generate(), xid, branchId, businessNo, source, accountId,
                counterpartyOwnerId, amountCent)) {
            BranchRow existing = repository.lockPendingCredit(xid, branchId).orElse(null);
            return existing != null
                    && existing.accountId().equals(accountId)
                    && existing.amountCent() == amountCent
                    && existing.businessNo().equals(businessNo)
                    && !"CANCELLED".equals(existing.status());
        }
        return true;
    }

    /** Backward-compatible branch adapter for already deployed callers during rolling upgrade. */
    public boolean tryCredit(
            String xid, long branchId, String businessNo, String source,
            UUID ownerId, UUID accountId, long amountCent) {
        return tryCredit(xid, branchId, businessNo, source, ownerId, accountId, null, amountCent);
    }

    @Transactional
    public boolean confirmCredit(String xid, long branchId) {
        BranchRow branch = repository.lockPendingCredit(xid, branchId).orElse(null);
        if (branch == null) return false;
        if ("CONFIRMED".equals(branch.status())) return true;
        if (!"TRY".equals(branch.status())) return false;
        AccountRow account = repository.lockAccountById(branch.accountId()).orElseThrow();
        repository.changeBalance(
                WalletRepository.TRANSFER_CLEARING_ACCOUNT, -branch.amountCent(), 0);
        repository.changeBalance(branch.accountId(), branch.amountCent(), 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "TRANSFER_IN",
                branch.businessNo(),
                branch.amountCent(),
                WalletRepository.TRANSFER_CLEARING_ACCOUNT,
                branch.accountId());
        UUID receiptBillId = UuidV7.generate();
        repository.insertBill(
                receiptBillId,
                account.ownerId(),
                branch.accountId(),
                "TRANSFER",
                branch.businessNo(),
                "INCOME",
                branch.amountCent(),
                "站内付款人",
                null,
                "SUCCEEDED",
                account.availableAmountCent() + branch.amountCent(),
                null,
                branch.source(),
                branch.counterpartyUserId());
        repository.updateBranchStatus("pending_credit", xid, branchId, "CONFIRMED");
        if ("PERSONAL_COLLECTION_CODE".equals(branch.source())) {
            events.publishEvent(new CollectionReceiptReady(
                    UuidV7.generate(), account.ownerId(), receiptBillId, branch.source(),
                    branch.amountCent(), "站内付款人", java.time.Instant.now()));
        }
        return true;
    }

    @Transactional
    public boolean cancelCredit(String xid, long branchId) {
        BranchRow branch = repository.lockPendingCredit(xid, branchId).orElse(null);
        if (branch == null) return true;
        if ("CANCELLED".equals(branch.status())) return true;
        if (!"TRY".equals(branch.status())) return false;
        repository.updateBranchStatus("pending_credit", xid, branchId, "CANCELLED");
        return true;
    }

    @Transactional(readOnly = true)
    public TransferOutcome outcome(
            String businessNo, UUID payerAccountId, UUID receiverAccountId) {
        String debit = repository.findFreezeByBusiness(businessNo, payerAccountId)
                .map(BranchRow::status)
                .orElse("NOT_FOUND");
        String credit = repository.findPendingCreditByBusiness(businessNo, receiverAccountId)
                .map(BranchRow::status)
                .orElse("NOT_FOUND");
        return new TransferOutcome(businessNo, debit, credit);
    }

    private static String compact(UUID value) {
        return value.toString().replace("-", "").toUpperCase();
    }

    public record TransferOutcome(
            String businessNo, String debitStatus, String creditStatus) {
    }
}
