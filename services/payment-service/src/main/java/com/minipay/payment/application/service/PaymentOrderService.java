package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.PaymentOrder;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.PaymentOrderRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantSettlementContextRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.PaymentResolutionRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentOrderService {
    private static final Set<String> METHODS =
            Set.of("ALIPAY", "WECHAT_PAY", "WALLET_BALANCE");

    private final PaymentRepository repository;
    private final MerchantRepository merchants;
    private final WalletInternalClient wallet;
    private final IdentityInternalClient identity;
    private final DomainEventWriter events;

    public PaymentOrderService(
            PaymentRepository repository,
            MerchantRepository merchants,
            WalletInternalClient wallet,
            IdentityInternalClient identity,
            DomainEventWriter events) {
        this.repository = repository;
        this.merchants = merchants;
        this.wallet = wallet;
        this.identity = identity;
        this.events = events;
    }

    @Transactional
    public PaymentOrder create(
            UUID userId,
            String idempotencyKey,
            long amountCent,
            String subject,
            String paymentMethod,
            UUID resolutionId) {
        String scopedKey = RequestSupport.scopedIdempotencyKey(userId, idempotencyKey);
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new PaymentProblemException(
                    "AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (subject == null || subject.isBlank() || subject.length() > 256) {
            throw new PaymentProblemException("INVALID_SUBJECT", HttpStatus.BAD_REQUEST);
        }
        if (!METHODS.contains(paymentMethod)) {
            throw new PaymentProblemException(
                    "UNSUPPORTED_PAYMENT_METHOD", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!"WALLET_BALANCE".equals(paymentMethod)
                && !repository.hasActiveBankCard(userId)) {
            throw new PaymentProblemException(
                    "ACTIVE_BANK_CARD_REQUIRED", HttpStatus.FORBIDDEN);
        }
        PaymentOrderRow existing = repository.findPaymentByIdempotency(userId, scopedKey)
                .orElse(null);
        if (existing != null) {
            if (existing.amountCent() != amountCent
                    || !existing.subject().equals(subject)
                    || !existing.paymentMethod().equals(paymentMethod)
                    || !java.util.Objects.equals(existing.resolutionId(), resolutionId)) {
                throw new PaymentProblemException(
                        "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
            }
            return existing.toPublicModel();
        }
        UUID paymentOrderId = UuidV7.generate();
        UUID attemptId = UuidV7.generate();
        String paymentOrderNo = RequestSupport.businessNo("P", paymentOrderId);
        PaymentResolutionRow resolution = resolutionId == null ? null
                : requireAvailableResolution(resolutionId, userId, paymentMethod);
        String merchantOrderNo = resolution == null ? scopedKey
                : RequestSupport.businessNo("M", paymentOrderId);
        String redirectUrl = switch (paymentMethod) {
            case "ALIPAY" -> "minipay://sandbox/alipay/" + paymentOrderId;
            case "WECHAT_PAY" -> "minipay://sandbox/wechat-pay/" + paymentOrderId;
            default -> null;
        };
        String annualLimitMode = repository.hasActiveBankCard(userId)
                ? "EXEMPT_ACTIVE_CARD" : "LIMITED";
        PaymentOrder order = repository.insertPaymentOrder(
                paymentOrderId,
                paymentOrderNo,
                attemptId,
                userId,
                scopedKey,
                amountCent,
                subject.strip(),
                paymentMethod,
                RequestSupport.businessNo("CH", attemptId),
                redirectUrl,
                Instant.now().plus(15, ChronoUnit.MINUTES),
                annualLimitMode,
                resolution == null ? null : resolution.merchantId(),
                resolution == null ? null : resolution.applicationId(),
                resolution == null ? "consumer-sandbox" : resolution.appId(),
                merchantOrderNo,
                resolutionId);
        if (resolutionId != null
                && !merchants.consumePaymentResolution(resolutionId, paymentOrderId)) {
            throw new PaymentProblemException(
                    "SCAN_RESOLUTION_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        return order;
    }

    public PaymentOrder create(
            UUID userId, String idempotencyKey, long amountCent,
            String subject, String paymentMethod) {
        return create(userId, idempotencyKey, amountCent, subject, paymentMethod, null);
    }

    @Transactional
    public PaymentOrder createMerchantOrder(
            UUID merchantId, UUID applicationId, String appId,
            UUID payerUserId, String merchantOrderNo,
            long amountCent, String subject, String paymentMethod) {
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new PaymentProblemException(
                    "AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (subject == null || subject.isBlank() || subject.length() > 256) {
            throw new PaymentProblemException("INVALID_SUBJECT", HttpStatus.BAD_REQUEST);
        }
        if (!METHODS.contains(paymentMethod)) {
            throw new PaymentProblemException(
                    "UNSUPPORTED_PAYMENT_METHOD", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        PaymentOrderRow existing = repository.findPaymentByMerchantOrder(appId, merchantOrderNo)
                .orElse(null);
        if (existing != null) {
            if (existing.amountCent() != amountCent
                    || !existing.subject().equals(subject.strip())
                    || !existing.paymentMethod().equals(paymentMethod)) {
                throw new PaymentProblemException(
                        "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
            }
            return existing.toPublicModel();
        }
        UUID paymentOrderId = UuidV7.generate();
        UUID attemptId = UuidV7.generate();
        String paymentOrderNo = RequestSupport.businessNo("P", paymentOrderId);
        String redirectUrl = switch (paymentMethod) {
            case "ALIPAY" -> "minipay://sandbox/alipay/" + paymentOrderId;
            case "WECHAT_PAY" -> "minipay://sandbox/wechat-pay/" + paymentOrderId;
            default -> null;
        };
        String annualLimitMode = repository.hasActiveBankCard(payerUserId)
                ? "EXEMPT_ACTIVE_CARD" : "LIMITED";
        try {
            return repository.insertPaymentOrder(
                    paymentOrderId, paymentOrderNo, attemptId, payerUserId, merchantOrderNo,
                    amountCent, subject.strip(), paymentMethod,
                    RequestSupport.businessNo("CH", attemptId), redirectUrl,
                    Instant.now().plus(15, ChronoUnit.MINUTES), annualLimitMode,
                    merchantId, applicationId, appId, merchantOrderNo, null);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            PaymentOrderRow row = repository.findPaymentByMerchantOrder(appId, merchantOrderNo)
                    .orElseThrow();
            if (row.amountCent() == amountCent && row.subject().equals(subject.strip())
                    && row.paymentMethod().equals(paymentMethod)) {
                return row.toPublicModel();
            }
            throw new PaymentProblemException(
                    "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
        }
    }

    public PaymentOrder confirmWalletBalance(
            UUID userId,
            UUID paymentOrderId,
            String paymentAuthToken,
            String deviceId) {
        PaymentOrderRow order = repository.findPayment(userId, paymentOrderId)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        if (!order.expiresAt().isAfter(Instant.now())) {
            expirePayment(order.userId(), order.paymentOrderId());
            throw new PaymentProblemException(
                    "PAYMENT_ORDER_EXPIRED", HttpStatus.CONFLICT);
        }
        if (order.authorizationId() == null) {
            UUID authorizationId = identity.verifyAndConsume(
                    paymentAuthToken,
                    userId,
                    "PAYMENT_ORDER",
                    paymentOrderId,
                    order.amountCent(),
                    deviceId);
            repository.authorizePayment(paymentOrderId, authorizationId);
        }
        if (!"WALLET_BALANCE".equals(order.paymentMethod())) {
            return get(userId, paymentOrderId);
        }
        postSuccessfulFunds(order, "WALLET_BALANCE");
        writeSucceededEvent(order, () -> repository.completePaymentOrder(
                paymentOrderId,
                "SUCCEEDED",
                RequestSupport.businessNo("WB", paymentOrderId),
                null));
        return get(userId, paymentOrderId);
    }

    public boolean expirePayment(UUID userId, UUID paymentOrderId) {
        PaymentOrderRow order = repository.findPayment(userId, paymentOrderId).orElse(null);
        if (order == null || !"PROCESSING".equals(order.status())
                || order.expiresAt().isAfter(Instant.now())) {
            return false;
        }
        writeClosedEvent(order, () -> repository.completePaymentOrder(
                paymentOrderId, "CLOSED", null, "PAYMENT_ORDER_EXPIRED"));
        return true;
    }

    public PaymentOrder get(UUID userId, UUID paymentOrderId) {
        return repository.findPayment(userId, paymentOrderId)
                .map(PaymentOrderRow::toPublicModel)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public PaymentOrder getFoodPayment(UUID userId, UUID foodOrderId) {
        return repository.findFoodPayment(userId, foodOrderId)
                .map(PaymentOrderRow::toPublicModel)
                .orElseThrow(() -> new PaymentProblemException(
                        "FOOD_PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public PaymentOrder completeSandboxExternal(
            UUID userId,
            UUID paymentOrderId,
            boolean succeeded) {
        PaymentOrderRow order = repository.findPayment(userId, paymentOrderId)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if ("WALLET_BALANCE".equals(order.paymentMethod())) {
            throw new PaymentProblemException(
                    "NOT_EXTERNAL_PAYMENT", HttpStatus.CONFLICT);
        }
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        if (succeeded) {
            postSuccessfulFunds(order, order.paymentMethod());
            writeSucceededEvent(order, () -> repository.completePaymentOrder(
                    paymentOrderId,
                    "SUCCEEDED",
                    RequestSupport.businessNo("EXT", paymentOrderId),
                    null));
        } else {
            writeFailedEvent(order, () -> repository.completePaymentOrder(
                    paymentOrderId,
                    "FAILED",
                    null,
                    "SANDBOX_CHANNEL_DECLINED"));
        }
        return get(userId, paymentOrderId);
    }

    public PaymentOrder completeTrustedSandboxCallback(
            UUID paymentOrderId, boolean succeeded) {
        UUID userId = repository.findPaymentOwner(paymentOrderId)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        PaymentOrderRow order = repository.findPayment(userId, paymentOrderId).orElseThrow();
        if (order.authorizationId() == null) {
            throw new PaymentProblemException(
                    "PAYMENT_AUTHORIZATION_INVALID", HttpStatus.CONFLICT);
        }
        return completeSandboxExternal(userId, paymentOrderId, succeeded);
    }

    private void writeSucceededEvent(PaymentOrderRow order, Runnable stateChange) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentOrderId", order.paymentOrderId());
        payload.put("paymentOrderNo", order.paymentOrderNo());
        payload.put("userId", order.userId());
        payload.put("amountCent", order.amountCent());
        payload.put("currency", order.currency());
        payload.put("paymentMethod", order.paymentMethod());
        if (order.merchantId() != null) payload.put("merchantId", order.merchantId());
        if (order.applicationId() != null) payload.put("applicationId", order.applicationId());
        if (order.appId() != null) payload.put("appId", order.appId());
        if (order.merchantOrderNo() != null) {
            payload.put("merchantOrderNo", order.merchantOrderNo());
        }
        UUID foodOrderId = repository.findFoodOrderId(order.paymentOrderId()).orElse(null);
        if (foodOrderId == null) {
            events.writeAfter(stateChange, "payment.order.succeeded", "PAYMENT_ORDER",
                    order.paymentOrderId(), payload);
            return;
        }
        Map<String, Object> foodPayload = new LinkedHashMap<>();
        foodPayload.put("foodOrderId", foodOrderId);
        foodPayload.put("paymentOrderId", order.paymentOrderId());
        foodPayload.put("amountCent", order.amountCent());
        foodPayload.put("currency", order.currency());
        events.writeAfterWithAdditional(stateChange,
                "payment.order.succeeded", "PAYMENT_ORDER", order.paymentOrderId(), payload,
                "payment.food-order.succeeded", "FOOD_ORDER", foodOrderId, foodPayload);
    }

    private void writeFailedEvent(PaymentOrderRow order, Runnable stateChange) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentOrderId", order.paymentOrderId());
        payload.put("paymentOrderNo", order.paymentOrderNo());
        payload.put("userId", order.userId());
        payload.put("amountCent", order.amountCent());
        payload.put("currency", order.currency());
        payload.put("paymentMethod", order.paymentMethod());
        payload.put("failureCode", "SANDBOX_CHANNEL_DECLINED");
        if (order.merchantId() != null) payload.put("merchantId", order.merchantId());
        if (order.applicationId() != null) payload.put("applicationId", order.applicationId());
        if (order.appId() != null) payload.put("appId", order.appId());
        if (order.merchantOrderNo() != null) {
            payload.put("merchantOrderNo", order.merchantOrderNo());
        }
        UUID foodOrderId = repository.findFoodOrderId(order.paymentOrderId()).orElse(null);
        if (foodOrderId == null) {
            events.writeAfter(stateChange, "payment.order.failed", "PAYMENT_ORDER",
                    order.paymentOrderId(), payload);
            return;
        }
        Map<String, Object> foodPayload = new LinkedHashMap<>();
        foodPayload.put("foodOrderId", foodOrderId);
        foodPayload.put("paymentOrderId", order.paymentOrderId());
        foodPayload.put("failureCode", "SANDBOX_CHANNEL_DECLINED");
        events.writeAfterWithAdditional(stateChange,
                "payment.order.failed", "PAYMENT_ORDER", order.paymentOrderId(), payload,
                "payment.food-order.failed", "FOOD_ORDER", foodOrderId, foodPayload);
    }

    private void writeClosedEvent(PaymentOrderRow order, Runnable stateChange) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentOrderId", order.paymentOrderId());
        payload.put("paymentOrderNo", order.paymentOrderNo());
        payload.put("userId", order.userId());
        payload.put("amountCent", order.amountCent());
        payload.put("currency", order.currency());
        payload.put("paymentMethod", order.paymentMethod());
        payload.put("failureCode", "PAYMENT_ORDER_EXPIRED");
        if (order.merchantId() != null) payload.put("merchantId", order.merchantId());
        if (order.applicationId() != null) payload.put("applicationId", order.applicationId());
        if (order.appId() != null) payload.put("appId", order.appId());
        if (order.merchantOrderNo() != null) {
            payload.put("merchantOrderNo", order.merchantOrderNo());
        }
        UUID foodOrderId = repository.findFoodOrderId(order.paymentOrderId()).orElse(null);
        if (foodOrderId == null) {
            events.writeAfter(stateChange, "payment.order.closed", "PAYMENT_ORDER",
                    order.paymentOrderId(), payload);
            return;
        }
        Map<String, Object> foodPayload = new LinkedHashMap<>();
        foodPayload.put("foodOrderId", foodOrderId);
        foodPayload.put("paymentOrderId", order.paymentOrderId());
        foodPayload.put("failureCode", "PAYMENT_ORDER_EXPIRED");
        events.writeAfterWithAdditional(stateChange,
                "payment.order.closed", "PAYMENT_ORDER", order.paymentOrderId(), payload,
                "payment.food-order.closed", "FOOD_ORDER", foodOrderId, foodPayload);
    }

    private PaymentResolutionRow requireAvailableResolution(
            UUID resolutionId, UUID payerUserId, String paymentMethod) {
        PaymentResolutionRow resolution = merchants.lockPaymentResolution(resolutionId)
                .orElseThrow(() -> new PaymentProblemException(
                        "SCAN_RESOLUTION_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (resolution.consumedAt() != null || !resolution.expiresAt().isAfter(Instant.now())) {
            throw new PaymentProblemException(
                    "SCAN_RESOLUTION_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        if (!"ACTIVE".equals(resolution.merchantStatus())
                || !"ACTIVE".equals(resolution.applicationStatus())) {
            throw new PaymentProblemException(
                    "MERCHANT_COLLECTION_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        if (payerUserId.equals(resolution.ownerUserId())) {
            throw new PaymentProblemException(
                    "SELF_MERCHANT_PAYMENT", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String channel = switch (paymentMethod) {
            case "WALLET_BALANCE" -> "WALLET";
            case "WECHAT_PAY" -> "WECHAT";
            default -> paymentMethod;
        };
        if (java.util.Arrays.stream(resolution.availableChannels().split(","))
                .map(String::strip).noneMatch(channel::equals)) {
            throw new PaymentProblemException(
                    "PAYMENT_CHANNEL_NOT_ENABLED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return resolution;
    }

    private void postSuccessfulFunds(PaymentOrderRow order, String fundingSource) {
        if (order.merchantId() == null) {
            if ("WALLET_BALANCE".equals(fundingSource)) {
                wallet.debitPayment(
                        order.paymentOrderId(), order.paymentOrderNo(), order.userId(),
                        order.amountCent(), order.subject(),
                        repository.paymentAnnualLimitMode(order.paymentOrderId()));
            }
            return;
        }
        MerchantSettlementContext settlement = merchantSettlement(order, fundingSource);
        wallet.postMerchantPayment(
                order.paymentOrderId(), order.paymentOrderNo(), order.userId(),
                settlement.ownerUserId(), order.amountCent(), order.subject(),
                repository.paymentAnnualLimitMode(order.paymentOrderId()),
                fundingSource, settlement.merchantName());
    }

    private MerchantSettlementContext merchantSettlement(PaymentOrderRow order, String fundingSource) {
        if (order.resolutionId() != null) {
            PaymentResolutionRow resolution = merchants.lockPaymentResolution(order.resolutionId())
                    .orElseThrow(() -> new PaymentProblemException(
                            "MERCHANT_CONTEXT_NOT_FOUND", HttpStatus.CONFLICT));
            return new MerchantSettlementContext(
                    resolution.ownerUserId(), resolution.merchantName(),
                    resolution.merchantStatus(), resolution.applicationStatus(),
                    resolution.availableChannels());
        }
        MerchantSettlementContextRow context = merchants.findMerchantSettlementContext(
                        order.merchantId(), order.applicationId())
                .orElseThrow(() -> new PaymentProblemException(
                        "MERCHANT_CONTEXT_NOT_FOUND", HttpStatus.CONFLICT));
        MerchantSettlementContext settlement = new MerchantSettlementContext(
                context.ownerUserId(), context.merchantName(), context.merchantStatus(),
                context.applicationStatus(), context.availableChannels());
        String requiredChannel = switch (fundingSource) {
            case "WALLET_BALANCE" -> "WALLET";
            case "WECHAT_PAY" -> "WECHAT";
            default -> fundingSource;
        };
        if (!"ACTIVE".equals(settlement.merchantStatus())
                || !"ACTIVE".equals(settlement.applicationStatus())) {
            throw new PaymentProblemException(
                    "MERCHANT_COLLECTION_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        if (java.util.Arrays.stream(settlement.availableChannels().split(","))
                .map(String::strip).noneMatch(requiredChannel::equals)) {
            throw new PaymentProblemException(
                    "PAYMENT_CHANNEL_NOT_ENABLED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (order.userId().equals(settlement.ownerUserId())) {
            throw new PaymentProblemException(
                    "SELF_MERCHANT_PAYMENT", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return settlement;
    }

    private record MerchantSettlementContext(
            UUID ownerUserId,
            String merchantName,
            String merchantStatus,
            String applicationStatus,
            String availableChannels) { }
}
