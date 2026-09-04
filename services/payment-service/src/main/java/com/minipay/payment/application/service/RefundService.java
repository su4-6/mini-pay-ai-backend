package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.RefundRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.RefundablePaymentRow;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RefundService {
    private final PaymentRepository repository;
    private final WalletInternalClient wallet;
    private final DomainEventWriter events;

    public RefundService(
            PaymentRepository repository,
            WalletInternalClient wallet,
            DomainEventWriter events) {
        this.repository = repository;
        this.wallet = wallet;
        this.events = events;
    }

    public Refund create(
            String idempotencyKey,
            UUID paymentOrderId,
            long amountCent,
            String reason) {
        if (idempotencyKey == null
                || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128) {
            throw new PaymentProblemException(
                    "INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
        }
        String requestNo = "ops:" + RequestSupport.hashText(idempotencyKey);
        RefundRow existing = repository.findRefundByRequestNo(requestNo).orElse(null);
        if (existing != null) {
            requireSameRequest(existing, paymentOrderId, amountCent);
            return resume(existing);
        }

        RefundablePaymentRow payment = repository.findRefundablePayment(paymentOrderId)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"SUCCEEDED".equals(payment.status())) {
            throw new PaymentProblemException(
                    "PAYMENT_NOT_REFUNDABLE", HttpStatus.CONFLICT);
        }
        if (payment.merchantId() == null
                && !"WALLET_BALANCE".equals(payment.paymentMethod())) {
            throw new PaymentProblemException(
                    "P0_REFUND_ONLY_SUPPORTS_WALLET_BALANCE",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (amountCent != payment.amountCent()) {
            throw new PaymentProblemException(
                    "P0_REFUND_MUST_BE_FULL_AMOUNT", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String normalizedReason = reason == null ? null : reason.strip();
        if (normalizedReason != null && normalizedReason.length() > 256) {
            throw new PaymentProblemException("INVALID_REFUND_REASON", HttpStatus.BAD_REQUEST);
        }

        UUID refundId = UuidV7.generate();
        RefundRow refund;
        try {
            refund = repository.insertRefund(
                    refundId,
                    RequestSupport.businessNo("R", refundId),
                    paymentOrderId,
                    requestNo,
                    amountCent,
                    normalizedReason);
        } catch (DuplicateKeyException exception) {
            refund = repository.findRefundByPayment(paymentOrderId)
                    .orElseThrow(() -> exception);
            if (!refund.merchantRefundNo().equals(requestNo)) {
                throw new PaymentProblemException(
                        "PAYMENT_ALREADY_REFUNDED", HttpStatus.CONFLICT);
            }
            requireSameRequest(refund, paymentOrderId, amountCent);
            return resume(refund);
        }
        return complete(refund, payment);
    }

    private Refund resume(RefundRow refund) {
        if (!"PROCESSING".equals(refund.status())) {
            return refund.toPublicModel();
        }
        RefundablePaymentRow payment = repository.findRefundablePayment(refund.paymentOrderId())
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        return complete(refund, payment);
    }

    private Refund complete(RefundRow refund, RefundablePaymentRow payment) {
        if (payment.merchantId() == null) {
            wallet.creditRefund(
                    refund.refundId(), refund.refundNo(), payment.userId(),
                    refund.amountCent(), payment.paymentOrderNo());
        } else {
            wallet.postMerchantRefund(
                    refund.refundId(), refund.refundNo(), payment.userId(),
                    payment.merchantOwnerUserId(), refund.amountCent(),
                    payment.paymentOrderNo(), payment.merchantName());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refund.refundId());
        payload.put("refundNo", refund.refundNo());
        payload.put("paymentOrderId", payment.paymentOrderId());
        payload.put("paymentOrderNo", payment.paymentOrderNo());
        payload.put("userId", payment.userId());
        payload.put("amountCent", refund.amountCent());
        payload.put("currency", payment.currency());
        payload.put("paymentMethod", payment.paymentMethod());
        repository.findFoodOrderId(payment.paymentOrderId())
                .ifPresent(foodOrderId -> payload.put("foodOrderId", foodOrderId));
        if (payment.merchantId() != null) payload.put("merchantId", payment.merchantId());
        if (payment.applicationId() != null) {
            payload.put("applicationId", payment.applicationId());
        }
        if (payment.appId() != null) payload.put("appId", payment.appId());
        if (payment.merchantOrderNo() != null) {
            payload.put("merchantOrderNo", payment.merchantOrderNo());
        }
        events.writeAfter(
                () -> repository.completeRefund(refund.refundId(), "SUCCEEDED", null),
                "payment.refund.succeeded",
                "REFUND_ORDER",
                refund.refundId(),
                payload);
        return repository.findRefundByRequestNo(refund.merchantRefundNo())
                .orElseThrow()
                .toPublicModel();
    }

    private static void requireSameRequest(
            RefundRow refund, UUID paymentOrderId, long amountCent) {
        if (!refund.paymentOrderId().equals(paymentOrderId)
                || refund.amountCent() != amountCent) {
            throw new PaymentProblemException(
                    "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
        }
    }
}
