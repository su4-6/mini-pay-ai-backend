package com.minipay.wallet.application.service;

import com.minipay.wallet.domain.model.BillPage;
import com.minipay.wallet.domain.model.BillQuery;
import com.minipay.wallet.domain.model.BillManagement;
import com.minipay.wallet.domain.model.BillTag;
import com.minipay.wallet.domain.model.BillTagPage;
import com.minipay.wallet.domain.model.CollectionRecordPage;
import com.minipay.wallet.domain.model.WalletBill;
import com.minipay.wallet.domain.model.WalletBillDetail;
import com.minipay.wallet.domain.model.WalletSummary;
import com.minipay.wallet.infrastructure.persistence.WalletRepository;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.AccountRow;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.PostingRow;
import com.minipay.wallet.infrastructure.persistence.AnnualOutflowRepository;
import com.minipay.wallet.infrastructure.client.IdentityProfileClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletApplicationService {
    public static final long INITIAL_GRANT_CENT = 1_000_000L;
    public static final long RECHARGE_BALANCE_CAP_CENT = 2_000_000L;
    public static final String SANDBOX_NOTICE =
            "演示沙箱资产；银行、支付宝和微信均为模拟通道，不代表真实资金。";

    private final WalletRepository repository;
    private final AnnualOutflowRepository annualOutflow;
    private final IdentityProfileClient profiles;
    private final ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    public WalletApplicationService(
            WalletRepository repository, AnnualOutflowRepository annualOutflow,
            IdentityProfileClient profiles, ApplicationEventPublisher events) {
        this.repository = repository;
        this.annualOutflow = annualOutflow;
        this.profiles = profiles;
        this.events = events;
    }

    public WalletApplicationService(
            WalletRepository repository, AnnualOutflowRepository annualOutflow) {
        this(repository, annualOutflow, null, null);
    }

    public CollectionRecordPage collectionRecords(
            UUID ownerId, String requestedType, String requestedPeriod, int page, int size) {
        requireExistingWallet(ownerId);
        String type = requestedType == null ? "ALL" : requestedType.strip().toUpperCase(Locale.ROOT);
        String period = requestedPeriod == null ? "TODAY" : requestedPeriod.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "PERSONAL", "MERCHANT").contains(type)) {
            throw new WalletProblemException("INVALID_COLLECTION_RECORD_TYPE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!Set.of("TODAY", "MONTH").contains(period)) {
            throw new WalletProblemException("INVALID_COLLECTION_RECORD_PERIOD", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var now = java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        var start = "MONTH".equals(period)
                ? now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone())
                : now.toLocalDate().atStartOfDay(now.getZone());
        Instant from = start.toInstant();
        Instant to = ("MONTH".equals(period) ? start.plusMonths(1) : start.plusDays(1)).toInstant();
        CollectionRecordPage result = repository.queryCollectionRecords(
                ownerId, type, period, from, to, page, size);
        var resolved = profileMap(result.items());
        return new CollectionRecordPage(
                result.items().stream().map(bill -> enrich(bill, resolved)).toList(),
                result.page(), result.size(), result.total(), result.type(), result.period(),
                result.from(), result.to(), result.summary());
    }

    public WalletSummary getWallet(UUID ownerId) {
        AccountRow account = repository.findConsumerAccount(ownerId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.NOT_FOUND));
        return summary(account);
    }

    public BillPage listBills(UUID ownerId, BillQuery query) {
        requireExistingWallet(ownerId);
        BillPage page = repository.queryBills(ownerId, query);
        var resolved = profileMap(page.items());
        return new BillPage(page.items().stream().map(bill -> enrich(bill, resolved)).toList(),
                page.page(), page.size(), page.total());
    }

    @Transactional(readOnly = true)
    public TransferRecordPage transferRecords(
            UUID ownerId, UUID counterpartyUserId, String direction, String status,
            java.time.Instant from, java.time.Instant to, int page, int size) {
        requireExistingWallet(ownerId);
        requireTransferDirection(direction);
        requireTransferStatus(status);
        var rows = repository.queryTransferRecords(ownerId, counterpartyUserId, direction, status,
                from, to, page, size);
        var resolved = profileMap(rows.items());
        return new TransferRecordPage(
                rows.items().stream().map(bill -> enrich(bill, resolved)).toList(),
                rows.page(), rows.size(), rows.total(),
                rows.months().stream().map(month -> new TransferMonthSummary(
                        month.month(), month.incomeAmountCent(), month.expenseAmountCent())).toList());
    }

    @Transactional(readOnly = true)
    public RecentTransferCounterpartyPage recentTransferCounterparties(UUID ownerId, int page, int size) {
        requireExistingWallet(ownerId);
        var rows = repository.queryRecentTransferCounterparties(ownerId, page, size);
        var profileMap = profiles == null ? java.util.Map.<UUID, com.minipay.wallet.domain.model.CounterpartyProfile>of()
                : profiles.findProfiles(rows.items().stream().map(WalletRepository.RecentCounterpartyRow::userId).toList());
        var items = rows.items().stream().map(row -> {
            var profile = profileMap.get(row.userId());
            return new RecentTransferCounterparty(
                    row.userId(), profile == null ? row.displayName() : profile.nickname(),
                    profile == null ? null : profile.avatarUrl(),
                    profile == null ? null : profile.avatarUrlExpiresAt(), row.lastTransferAt());
        }).toList();
        return new RecentTransferCounterpartyPage(items, rows.page(), rows.size(), rows.total());
    }

    private static void requireTransferDirection(String value) {
        if (value != null && !Set.of("INCOME", "EXPENSE").contains(value)) {
            throw new WalletProblemException("INVALID_TRANSFER_DIRECTION", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static void requireTransferStatus(String value) {
        if (value != null && !Set.of("PROCESSING", "SUCCEEDED", "FAILED").contains(value)) {
            throw new WalletProblemException("INVALID_TRANSFER_STATUS", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(readOnly = true)
    public BillAggregation aggregateBills(UUID ownerId, BillQuery query) {
        requireExistingWallet(ownerId);
        var groups = repository.aggregateBills(ownerId, query).stream()
                .map(row -> new BillAggregation.Group(
                        row.direction(), row.businessType(), row.billCount(), row.totalAmountCent()))
                .toList();
        long income = groups.stream().filter(group -> "INCOME".equals(group.direction()))
                .mapToLong(BillAggregation.Group::totalAmountCent).sum();
        long expense = groups.stream().filter(group -> "EXPENSE".equals(group.direction()))
                .mapToLong(BillAggregation.Group::totalAmountCent).sum();
        return new BillAggregation(query.from(), query.to(), income, expense, groups);
    }

    public WalletBillDetail getBill(UUID ownerId, UUID billId) {
        WalletBill bill = repository.findBill(ownerId, billId)
                .orElseThrow(() -> new WalletProblemException(
                        "BILL_NOT_FOUND", HttpStatus.NOT_FOUND));
        return new WalletBillDetail(enrich(bill, profileMap(List.of(bill))), management(ownerId, bill));
    }

    @Transactional
    public WalletBillDetail updateBillManagement(
            UUID ownerId, UUID billId, String categoryCode, List<UUID> tagIds,
            String userNote, boolean includedInStatistics) {
        WalletBill bill = repository.findBill(ownerId, billId)
                .orElseThrow(() -> new WalletProblemException("BILL_NOT_FOUND", HttpStatus.NOT_FOUND));
        requireCategory(categoryCode);
        List<UUID> distinctTags = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
        if (distinctTags.size() > 5) {
            throw new WalletProblemException("BILL_TAG_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (repository.countOwnedTags(ownerId, distinctTags) != distinctTags.size()) {
            throw new WalletProblemException("BILL_TAG_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        String normalizedNote = normalizeNote(userNote);
        repository.upsertBillManagement(
                ownerId, billId, categoryCode, normalizedNote, includedInStatistics);
        repository.replaceBillTags(ownerId, billId, distinctTags);
        return new WalletBillDetail(bill, management(ownerId, bill));
    }

    @Transactional(readOnly = true)
    public BillTagPage listTags(UUID ownerId, int page, int size) {
        requireExistingWallet(ownerId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        var result = repository.queryTags(ownerId, safePage, safeSize);
        return new BillTagPage(result.items(), safePage, safeSize, result.total());
    }

    @Transactional
    public BillTag createTag(UUID ownerId, String idempotencyKey, String name) {
        requireExistingWallet(ownerId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new WalletProblemException("INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
        }
        String trimmed = name == null ? "" : name.strip();
        int characters = trimmed.codePointCount(0, trimmed.length());
        if (characters < 1 || characters > 12) {
            throw new WalletProblemException("TAG_NAME_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        byte[] requestHash = sha256("TAG:" + trimmed);
        var previous = repository.findTagByIdempotency(ownerId, idempotencyKey).orElse(null);
        if (previous != null) {
            if (!Arrays.equals(previous.requestHash(), requestHash)) {
                throw new WalletProblemException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
            }
            return previous.tag();
        }
        if (repository.countTags(ownerId) >= 50) {
            throw new WalletProblemException("TAG_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            return repository.insertTag(UuidV7.generate(), ownerId, trimmed,
                    trimmed.toLowerCase(Locale.ROOT), idempotencyKey, requestHash);
        } catch (DuplicateKeyException exception) {
            var concurrent = repository.findTagByIdempotency(ownerId, idempotencyKey).orElse(null);
            if (concurrent != null && Arrays.equals(concurrent.requestHash(), requestHash)) {
                return concurrent.tag();
            }
            throw new WalletProblemException("TAG_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
    }

    private BillManagement management(UUID ownerId, WalletBill bill) {
        var stored = repository.findBillManagement(ownerId, bill.billId()).orElse(null);
        return new BillManagement(
                stored == null ? defaultCategory(bill.businessType()) : stored.categoryCode(),
                repository.findBillTags(ownerId, bill.billId()),
                stored == null ? null : stored.userNote(),
                stored == null || stored.includedInStatistics());
    }

    private java.util.Map<UUID, com.minipay.wallet.domain.model.CounterpartyProfile> profileMap(
            List<WalletBill> bills) {
        if (profiles == null) return java.util.Map.of();
        return profiles.findProfiles(bills.stream().map(WalletBill::counterpartyUserId).toList());
    }

    private static WalletBill enrich(
            WalletBill bill,
            java.util.Map<UUID, com.minipay.wallet.domain.model.CounterpartyProfile> profiles) {
        var profile = bill.counterpartyUserId() == null
                ? null : profiles.get(bill.counterpartyUserId());
        return new WalletBill(bill.billId(), bill.businessType(), bill.businessNo(), bill.source(),
                bill.direction(), bill.amountCent(), bill.counterpartyDisplay(),
                bill.counterpartyUserId(), profile, bill.remark(), bill.status(),
                bill.balanceAfterCent(), bill.failureCode(), bill.occurredAt(), bill.updatedAt());
    }

    private static final Set<String> BILL_CATEGORIES = Set.of(
            "TRANSFER", "FUNDING", "DINING", "SHOPPING", "TRANSPORT",
            "LIFE_SERVICE", "MEDICAL", "EDUCATION", "ENTERTAINMENT", "REFUND", "OTHER");

    private static void requireCategory(String value) {
        if (!BILL_CATEGORIES.contains(value)) {
            throw new WalletProblemException("INVALID_BILL_CATEGORY", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static String defaultCategory(String businessType) {
        return switch (businessType) {
            case "TRANSFER" -> "TRANSFER";
            case "RECHARGE", "WITHDRAWAL", "WITHDRAWAL_REVERSAL" -> "FUNDING";
            case "REFUND", "MERCHANT_REFUND", "REVERSAL" -> "REFUND";
            case "PAYMENT", "MERCHANT_PAYMENT" -> "SHOPPING";
            default -> "OTHER";
        };
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > 200) {
            throw new WalletProblemException("BILL_NOTE_TOO_LONG", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return trimmed;
    }

    @Transactional
    public PostingResult openWallet(UUID eventId, UUID userId) {
        String idempotencyKey = "event:" + eventId;
        String businessNo = "OPEN" + compact(userId);
        byte[] requestHash = sha256("OPENING_GRANT:" + userId);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, userId, "OPENING_GRANT", businessNo);
        if (previous != null) {
            return previous;
        }

        repository.insertConsumerAccount(
                UuidV7.generate(),
                "W" + compact(userId),
                userId);
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new IllegalStateException("Wallet creation did not converge"));
        if (!repository.insertPosting(
                idempotencyKey, requestHash, "OPENING_GRANT", businessNo)) {
            return existingBusinessResult(userId, "OPENING_GRANT", businessNo);
        }

        repository.changeBalance(
                WalletRepository.SANDBOX_ISSUANCE_ACCOUNT,
                -INITIAL_GRANT_CENT,
                0);
        repository.changeBalance(account.accountId(), INITIAL_GRANT_CENT, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "OPENING_GRANT",
                businessNo,
                INITIAL_GRANT_CENT,
                WalletRepository.SANDBOX_ISSUANCE_ACCOUNT,
                account.accountId());
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "OPENING_GRANT",
                businessNo,
                "INCOME",
                INITIAL_GRANT_CENT,
                "MiniPay 沙箱",
                "首次开户演示资产",
                "SUCCEEDED",
                INITIAL_GRANT_CENT,
                null);
        repository.completePosting(idempotencyKey, billId);
        return new PostingResult(repository.findBill(userId, billId).orElseThrow(), true);
    }

    @Transactional
    public PostingResult postRecharge(
            UUID eventId,
            UUID rechargeId,
            String rechargeNo,
            UUID userId,
            long amountCent) {
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new WalletProblemException("AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String idempotencyKey = "event:" + eventId;
        byte[] requestHash = sha256(
                "RECHARGE:" + rechargeId + ":" + rechargeNo + ":" + userId + ":" + amountCent);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, userId, "RECHARGE", rechargeNo);
        if (previous != null) {
            return previous;
        }
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        requireActive(account);
        if (!repository.insertPosting(idempotencyKey, requestHash, "RECHARGE", rechargeNo)) {
            return existingBusinessResult(userId, "RECHARGE", rechargeNo);
        }
        if (account.availableAmountCent() + account.frozenAmountCent() + amountCent
                > RECHARGE_BALANCE_CAP_CENT) {
            throw new WalletProblemException(
                    "RECHARGE_BALANCE_LIMIT_EXCEEDED", HttpStatus.CONFLICT);
        }
        repository.changeBalance(
                WalletRepository.BANK_SETTLEMENT_ACCOUNT,
                -amountCent,
                0);
        repository.creditRechargeWithCap(
                account.accountId(), amountCent, RECHARGE_BALANCE_CAP_CENT);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "RECHARGE",
                rechargeNo,
                amountCent,
                WalletRepository.BANK_SETTLEMENT_ACCOUNT,
                account.accountId());
        UUID billId = UuidV7.generate();
        long balanceAfter = account.availableAmountCent() + amountCent;
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "RECHARGE",
                rechargeNo,
                "INCOME",
                amountCent,
                "MiniPay 模拟通道",
                "演示资产充值",
                "SUCCEEDED",
                balanceAfter,
                null);
        repository.completePosting(idempotencyKey, billId);
        return new PostingResult(repository.findBill(userId, billId).orElseThrow(), true);
    }

    @Transactional
    public PostingResult postWithdrawal(
            UUID withdrawalId,
            String withdrawalNo,
            UUID userId,
            long amountCent) {
        validateOperationAmount(amountCent);
        String idempotencyKey = "withdrawal:" + withdrawalId;
        byte[] requestHash = sha256(
                "WITHDRAWAL:" + withdrawalId + ":" + withdrawalNo + ":" + userId + ":"
                        + amountCent);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, userId, "WITHDRAWAL", withdrawalNo);
        if (previous != null) {
            return previous;
        }
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        requireActive(account);
        if (!repository.insertPosting(
                idempotencyKey, requestHash, "WITHDRAWAL", withdrawalNo)) {
            return existingBusinessResult(userId, "WITHDRAWAL", withdrawalNo);
        }
        if (account.availableAmountCent() < amountCent) {
            throw new WalletProblemException(
                    "INSUFFICIENT_AVAILABLE_BALANCE", HttpStatus.CONFLICT);
        }
        repository.changeBalance(account.accountId(), -amountCent, 0);
        repository.changeBalance(WalletRepository.BANK_SETTLEMENT_ACCOUNT, amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "WITHDRAWAL",
                withdrawalNo,
                amountCent,
                account.accountId(),
                WalletRepository.BANK_SETTLEMENT_ACCOUNT);
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "WITHDRAWAL",
                withdrawalNo,
                "EXPENSE",
                amountCent,
                "绑定银行卡",
                "银行卡提现处理中",
                "PROCESSING",
                account.availableAmountCent() - amountCent,
                null);
        repository.completePosting(idempotencyKey, billId);
        return new PostingResult(repository.findBill(userId, billId).orElseThrow(), true);
    }

    @Transactional
    public WalletBill completeWithdrawal(UUID userId, String withdrawalNo) {
        repository.updateBillStatus(
                userId,
                "WITHDRAWAL",
                withdrawalNo,
                "PROCESSING",
                "SUCCEEDED",
                null);
        return repository.findBillByBusiness(userId, "WITHDRAWAL", withdrawalNo)
                .orElseThrow();
    }

    @Transactional
    public WalletBill reverseWithdrawal(
            UUID withdrawalId,
            UUID userId,
            String withdrawalNo,
            long amountCent,
            String failureCode) {
        validateOperationAmount(amountCent);
        WalletBill existingReversal = repository.findBillByBusiness(
                userId, "WITHDRAWAL_REVERSAL", withdrawalNo).orElse(null);
        if (existingReversal != null) {
            return existingReversal;
        }
        WalletBill original = repository.findBillByBusiness(
                        userId, "WITHDRAWAL", withdrawalNo)
                .orElseThrow(() -> new WalletProblemException(
                        "WITHDRAWAL_POSTING_NOT_FOUND", HttpStatus.NOT_FOUND));
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        repository.changeBalance(WalletRepository.BANK_SETTLEMENT_ACCOUNT, -amountCent, 0);
        repository.changeBalance(account.accountId(), amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "WITHDRAWAL_REVERSAL",
                withdrawalNo,
                amountCent,
                WalletRepository.BANK_SETTLEMENT_ACCOUNT,
                account.accountId());
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "WITHDRAWAL_REVERSAL",
                withdrawalNo,
                "INCOME",
                amountCent,
                "绑定银行卡",
                "提现失败冲正",
                "SUCCEEDED",
                account.availableAmountCent() + amountCent,
                failureCode);
        if ("PROCESSING".equals(original.status())) {
            repository.updateBillStatus(
                    userId,
                    "WITHDRAWAL",
                    withdrawalNo,
                    "PROCESSING",
                    "FAILED",
                    failureCode);
        }
        return repository.findBill(userId, billId).orElseThrow();
    }

    @Transactional
    public PostingResult postWalletPayment(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID userId,
            long amountCent,
            String subject,
            String annualLimitMode) {
        validateOperationAmount(amountCent);
        String idempotencyKey = "payment:" + paymentOrderId;
        byte[] requestHash = sha256(
                "PAYMENT:" + paymentOrderId + ":" + paymentOrderNo + ":" + userId + ":"
                        + amountCent);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, userId, "PAYMENT", paymentOrderNo);
        if (previous != null) {
            return previous;
        }
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        requireActive(account);
        if (!repository.insertPosting(
                idempotencyKey, requestHash, "PAYMENT", paymentOrderNo)) {
            return existingBusinessResult(userId, "PAYMENT", paymentOrderNo);
        }
        if ("LIMITED".equals(annualLimitMode)) {
            annualOutflow.consume(userId, "PAYMENT", paymentOrderNo, amountCent);
        } else if (!"EXEMPT_ACTIVE_CARD".equals(annualLimitMode)) {
            throw new WalletProblemException("INVALID_ANNUAL_LIMIT_MODE", HttpStatus.BAD_REQUEST);
        }
        if (account.availableAmountCent() < amountCent) {
            throw new WalletProblemException(
                    "INSUFFICIENT_AVAILABLE_BALANCE", HttpStatus.CONFLICT);
        }
        repository.changeBalance(account.accountId(), -amountCent, 0);
        repository.changeBalance(WalletRepository.BANK_SETTLEMENT_ACCOUNT, amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "PAYMENT",
                paymentOrderNo,
                amountCent,
                account.accountId(),
                WalletRepository.BANK_SETTLEMENT_ACCOUNT);
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "PAYMENT",
                paymentOrderNo,
                "EXPENSE",
                amountCent,
                "MiniPay 沙箱商户",
                subject,
                "SUCCEEDED",
                account.availableAmountCent() - amountCent,
                null);
        repository.completePosting(idempotencyKey, billId);
        return new PostingResult(repository.findBill(userId, billId).orElseThrow(), true);
    }

    @Transactional
    public PostingResult postRefund(
            UUID refundId,
            String refundNo,
            UUID userId,
            long amountCent,
            String originalPaymentNo) {
        validateOperationAmount(amountCent);
        String idempotencyKey = "refund:" + refundId;
        byte[] requestHash = sha256(
                "REFUND:" + refundId + ":" + refundNo + ":" + userId + ":" + amountCent);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, userId, "REFUND", refundNo);
        if (previous != null) return previous;
        AccountRow account = repository.lockConsumerAccount(userId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        requireActive(account);
        if (!repository.insertPosting(idempotencyKey, requestHash, "REFUND", refundNo)) {
            return existingBusinessResult(userId, "REFUND", refundNo);
        }
        repository.changeBalance(WalletRepository.BANK_SETTLEMENT_ACCOUNT, -amountCent, 0);
        repository.changeBalance(account.accountId(), amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "REFUND",
                refundNo,
                amountCent,
                WalletRepository.BANK_SETTLEMENT_ACCOUNT,
                account.accountId());
        UUID billId = UuidV7.generate();
        repository.insertBill(
                billId,
                userId,
                account.accountId(),
                "REFUND",
                refundNo,
                "INCOME",
                amountCent,
                "MiniPay 沙箱商户",
                "支付退款",
                "SUCCEEDED",
                account.availableAmountCent() + amountCent,
                null);
        repository.completePosting(idempotencyKey, billId);
        annualOutflow.releasePayment(userId, originalPaymentNo, refundNo, amountCent);
        return new PostingResult(repository.findBill(userId, billId).orElseThrow(), true);
    }

    /**
     * Posts both sides of a merchant payment in one wallet database transaction.
     * WALLET_BALANCE debits the payer wallet; sandbox external channels debit the
     * platform settlement account. In both cases the merchant owner's personal
     * wallet is the credit account, so no second "merchant wallet" is created.
     */
    @Transactional
    public PostingResult postMerchantPayment(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID payerUserId,
            UUID merchantOwnerUserId,
            long amountCent,
            String subject,
            String annualLimitMode,
            String fundingSource,
            String merchantName) {
        validateOperationAmount(amountCent);
        if (payerUserId.equals(merchantOwnerUserId)) {
            throw new WalletProblemException(
                    "SELF_MERCHANT_PAYMENT", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!java.util.Set.of("WALLET_BALANCE", "ALIPAY", "WECHAT_PAY")
                .contains(fundingSource)) {
            throw new WalletProblemException(
                    "UNSUPPORTED_PAYMENT_METHOD", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String idempotencyKey = "merchant-payment:" + paymentOrderId;
        byte[] requestHash = sha256(
                "MERCHANT_PAYMENT:" + paymentOrderId + ":" + paymentOrderNo + ":"
                        + payerUserId + ":" + merchantOwnerUserId + ":" + amountCent
                        + ":" + fundingSource);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, payerUserId,
                "MERCHANT_PAYMENT", paymentOrderNo);
        if (previous != null) return previous;

        AccountPair pair = lockAccountPair(payerUserId, merchantOwnerUserId);
        AccountRow payer = pair.forOwner(payerUserId);
        AccountRow owner = pair.forOwner(merchantOwnerUserId);
        requireActive(payer);
        requireActive(owner);
        if (!repository.insertPosting(
                idempotencyKey, requestHash, "MERCHANT_PAYMENT", paymentOrderNo)) {
            return existingBusinessResult(
                    payerUserId, "MERCHANT_PAYMENT", paymentOrderNo);
        }

        UUID debitAccountId;
        if ("WALLET_BALANCE".equals(fundingSource)) {
            if ("LIMITED".equals(annualLimitMode)) {
                annualOutflow.consume(
                        payerUserId, "PAYMENT", paymentOrderNo, amountCent);
            } else if (!"EXEMPT_ACTIVE_CARD".equals(annualLimitMode)) {
                throw new WalletProblemException(
                        "INVALID_ANNUAL_LIMIT_MODE", HttpStatus.BAD_REQUEST);
            }
            if (payer.availableAmountCent() < amountCent) {
                throw new WalletProblemException(
                        "INSUFFICIENT_AVAILABLE_BALANCE", HttpStatus.CONFLICT);
            }
            debitAccountId = payer.accountId();
            repository.changeBalance(payer.accountId(), -amountCent, 0);
        } else {
            debitAccountId = WalletRepository.BANK_SETTLEMENT_ACCOUNT;
            repository.changeBalance(debitAccountId, -amountCent, 0);
        }
        repository.changeBalance(owner.accountId(), amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId,
                "L" + compact(transactionId),
                "MERCHANT_PAYMENT",
                paymentOrderNo,
                amountCent,
                debitAccountId,
                owner.accountId());

        UUID payerBillId = UuidV7.generate();
        repository.insertBill(
                payerBillId, payerUserId, payer.accountId(), "MERCHANT_PAYMENT",
                paymentOrderNo, "EXPENSE", amountCent, merchantName, subject,
                "SUCCEEDED",
                "WALLET_BALANCE".equals(fundingSource)
                        ? payer.availableAmountCent() - amountCent
                        : payer.availableAmountCent(),
                null, "MERCHANT_PAYMENT", merchantOwnerUserId);
        UUID ownerBillId = UuidV7.generate();
        repository.insertBill(
                ownerBillId, merchantOwnerUserId, owner.accountId(), "MERCHANT_PAYMENT",
                paymentOrderNo, "INCOME", amountCent, "MiniPay 用户", subject,
                "SUCCEEDED", owner.availableAmountCent() + amountCent, null,
                "MERCHANT_PAYMENT", payerUserId);
        publishCollectionRecord(merchantOwnerUserId, ownerBillId, "MERCHANT_PAYMENT", amountCent);
        repository.completePosting(idempotencyKey, payerBillId);
        return new PostingResult(
                repository.findBill(payerUserId, payerBillId).orElseThrow(), true);
    }

    /** Reverses a merchant payment atomically: merchant owner pays, payer receives. */
    @Transactional
    public PostingResult postMerchantRefund(
            UUID refundId,
            String refundNo,
            UUID payerUserId,
            UUID merchantOwnerUserId,
            long amountCent,
            String originalPaymentNo,
            String merchantName) {
        validateOperationAmount(amountCent);
        String idempotencyKey = "merchant-refund:" + refundId;
        byte[] requestHash = sha256(
                "MERCHANT_REFUND:" + refundId + ":" + refundNo + ":"
                        + payerUserId + ":" + merchantOwnerUserId + ":" + amountCent
                        + ":" + originalPaymentNo);
        PostingResult previous = existingPosting(
                idempotencyKey, requestHash, payerUserId,
                "MERCHANT_REFUND", refundNo);
        if (previous != null) return previous;

        AccountPair pair = lockAccountPair(payerUserId, merchantOwnerUserId);
        AccountRow payer = pair.forOwner(payerUserId);
        AccountRow owner = pair.forOwner(merchantOwnerUserId);
        requireActive(payer);
        requireActive(owner);
        if (!repository.insertPosting(
                idempotencyKey, requestHash, "MERCHANT_REFUND", refundNo)) {
            return existingBusinessResult(payerUserId, "MERCHANT_REFUND", refundNo);
        }
        if (owner.availableAmountCent() < amountCent) {
            throw new WalletProblemException(
                    "MERCHANT_REFUND_INSUFFICIENT_BALANCE", HttpStatus.CONFLICT);
        }
        repository.changeBalance(owner.accountId(), -amountCent, 0);
        repository.changeBalance(payer.accountId(), amountCent, 0);
        UUID transactionId = UuidV7.generate();
        repository.insertLedger(
                transactionId, "L" + compact(transactionId), "MERCHANT_REFUND",
                refundNo, amountCent, owner.accountId(), payer.accountId());

        UUID payerBillId = UuidV7.generate();
        repository.insertBill(
                payerBillId, payerUserId, payer.accountId(), "MERCHANT_REFUND",
                refundNo, "INCOME", amountCent, merchantName, "商户退款",
                "SUCCEEDED", payer.availableAmountCent() + amountCent, null,
                "MERCHANT_REFUND", merchantOwnerUserId);
        UUID ownerBillId = UuidV7.generate();
        repository.insertBill(
                ownerBillId, merchantOwnerUserId, owner.accountId(), "MERCHANT_REFUND",
                refundNo, "EXPENSE", amountCent, "MiniPay 用户", "订单退款",
                "SUCCEEDED", owner.availableAmountCent() - amountCent, null,
                "MERCHANT_REFUND", payerUserId);
        publishCollectionRecord(merchantOwnerUserId, ownerBillId, "MERCHANT_REFUND", amountCent);
        repository.completePosting(idempotencyKey, payerBillId);
        annualOutflow.releasePayment(
                payerUserId, originalPaymentNo, refundNo, amountCent);
        return new PostingResult(
                repository.findBill(payerUserId, payerBillId).orElseThrow(), true);
    }

    private void publishCollectionRecord(UUID ownerId, UUID billId, String source, long amountCent) {
        if (events == null) return;
        WalletBill bill = repository.findBill(ownerId, billId).orElseThrow();
        events.publishEvent(new CollectionReceiptReady(
                UuidV7.generate(), ownerId, billId, source, amountCent,
                bill.counterpartyDisplay(), bill.occurredAt()));
    }

    @Transactional(readOnly = true)
    public ResolvedAccount resolve(UUID ownerId) {
        return repository.findConsumerAccount(ownerId)
                .map(account -> new ResolvedAccount(
                        ownerId, account.accountId(), account.status()))
                .orElseGet(() -> new ResolvedAccount(
                        ownerId, null, "PROVISIONING"));
    }

    private PostingResult existingPosting(
            String idempotencyKey,
            byte[] requestHash,
            UUID ownerId,
            String businessType,
            String businessNo) {
        PostingRow existing = repository.findPosting(idempotencyKey).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!Arrays.equals(existing.requestHash(), requestHash)
                || !businessType.equals(existing.businessType())
                || !businessNo.equals(existing.businessNo())) {
            throw new WalletProblemException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
        }
        WalletBill bill = existing.resultBillId() == null
                ? repository.findBillByBusiness(ownerId, businessType, businessNo).orElse(null)
                : repository.findBill(ownerId, existing.resultBillId()).orElse(null);
        return new PostingResult(bill, false);
    }

    private PostingResult existingBusinessResult(
            UUID ownerId, String businessType, String businessNo) {
        WalletBill existing = repository.findBillByBusiness(ownerId, businessType, businessNo)
                .orElseThrow(() -> new WalletProblemException(
                        "POSTING_CONFLICT", HttpStatus.CONFLICT));
        return new PostingResult(existing, false);
    }

    private WalletSummary summary(AccountRow account) {
        var limit = annualOutflow.current(account.ownerId());
        var recent = repository.recentBills(account.ownerId(), 5);
        var resolvedProfiles = profileMap(recent);
        return new WalletSummary(
                account.accountId(),
                account.availableAmountCent(),
                account.frozenAmountCent(),
                account.availableAmountCent() + account.frozenAmountCent(),
                account.currency(),
                account.status(),
                limit.year(),
                limit.limitAmountCent(),
                limit.usedAmountCent(),
                Math.max(0, limit.limitAmountCent() - limit.usedAmountCent()
                        - limit.reservedAmountCent()),
                SANDBOX_NOTICE,
                recent.stream().map(bill -> enrich(bill, resolvedProfiles)).toList());
    }

    private AccountRow requireExistingWallet(UUID ownerId) {
        return repository.findConsumerAccount(ownerId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.NOT_FOUND));
    }

    private AccountPair lockAccountPair(UUID firstOwner, UUID secondOwner) {
        UUID lower = firstOwner.toString().compareTo(secondOwner.toString()) <= 0
                ? firstOwner : secondOwner;
        UUID higher = lower.equals(firstOwner) ? secondOwner : firstOwner;
        AccountRow first = repository.lockConsumerAccount(lower)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        AccountRow second = repository.lockConsumerAccount(higher)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_PROVISIONING", HttpStatus.CONFLICT));
        return new AccountPair(first, second);
    }

    private void requireActive(AccountRow account) {
        if (!"ACTIVE".equals(account.status())) {
            throw new WalletProblemException("WALLET_SUSPENDED", HttpStatus.CONFLICT);
        }
    }

    private void validateOperationAmount(long amountCent) {
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new WalletProblemException(
                    "AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static String compact(UUID value) {
        return value.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record PostingResult(WalletBill bill, boolean created) {
    }

    public record ResolvedAccount(
            UUID ownerId, UUID accountId, String status) {
    }

    public record BillAggregation(
            java.time.Instant from,
            java.time.Instant to,
            long incomeAmountCent,
            long expenseAmountCent,
            List<Group> groups) {
        public BillAggregation {
            groups = List.copyOf(groups);
        }

        public record Group(
                String direction,
                String businessType,
                long billCount,
                long totalAmountCent) {
        }
    }

    public record TransferMonthSummary(String month, long incomeAmountCent, long expenseAmountCent) {}
    public record TransferRecordPage(List<WalletBill> items, int page, int size, long total,
                                     List<TransferMonthSummary> months) {}
    public record RecentTransferCounterparty(UUID userId, String nickname, String avatarUrl,
                                             java.time.Instant avatarUrlExpiresAt,
                                             java.time.Instant lastTransferAt) {}
    public record RecentTransferCounterpartyPage(List<RecentTransferCounterparty> items,
                                                 int page, int size, long total) {}

    private record AccountPair(AccountRow first, AccountRow second) {
        AccountRow forOwner(UUID ownerId) {
            if (first.ownerId().equals(ownerId)) return first;
            if (second.ownerId().equals(ownerId)) return second;
            throw new IllegalStateException("Locked wallet owner mismatch");
        }
    }
}
