package com.minipay.commerce.domain.model;

import java.time.Instant;
import java.util.UUID;

public record FoodOrder(
        UUID id,
        String orderNo,
        UUID userId,
        UUID merchantId,
        String merchantName,
        UUID addressId,
        String addressSummary,
        UUID quoteId,
        UUID paymentOrderId,
        long itemAmountCent,
        long deliveryFeeCent,
        long discountCent,
        long payableAmountCent,
        FoodOrderStatus status,
        PaymentStatus paymentStatus,
        RefundStatus refundStatus,
        Instant expiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public FoodOrder {
        if (id == null || userId == null || merchantId == null || addressId == null || quoteId == null) {
            throw new IllegalArgumentException("Order identifiers are required");
        }
        if (payableAmountCent <= 0 || itemAmountCent < 0 || deliveryFeeCent < 0
                || discountCent < 0 || payableAmountCent != itemAmountCent + deliveryFeeCent - discountCent) {
            throw new IllegalArgumentException("Order amounts are inconsistent");
        }
    }

    public FoodOrder paymentSucceeded(UUID authoritativePaymentOrderId, long authoritativeAmountCent, Instant now) {
        if (paymentStatus == PaymentStatus.SUCCEEDED
                && authoritativePaymentOrderId.equals(paymentOrderId)) return this;
        require(status == FoodOrderStatus.PENDING_PAYMENT, "COMMERCE_ORDER_STATE_CONFLICT");
        require(authoritativeAmountCent == payableAmountCent, "COMMERCE_PAYMENT_AMOUNT_MISMATCH");
        return copy(FoodOrderStatus.PAID, PaymentStatus.SUCCEEDED, RefundStatus.NONE,
                authoritativePaymentOrderId, now);
    }

    public FoodOrder paymentFailed(Instant now) {
        if (paymentStatus == PaymentStatus.FAILED) return this;
        require(status == FoodOrderStatus.PENDING_PAYMENT, "COMMERCE_ORDER_STATE_CONFLICT");
        return copy(FoodOrderStatus.PENDING_PAYMENT, PaymentStatus.FAILED, refundStatus, paymentOrderId, now);
    }

    public FoodOrder requestCancellation(Instant now) {
        if (status == FoodOrderStatus.CANCELLED || status == FoodOrderStatus.CANCELLATION_PENDING) return this;
        if (status == FoodOrderStatus.PENDING_PAYMENT) {
            return copy(FoodOrderStatus.CANCELLED, paymentStatus, RefundStatus.NONE, paymentOrderId, now);
        }
        require(status == FoodOrderStatus.PAID || status == FoodOrderStatus.REFUND_FAILED,
                "COMMERCE_ORDER_NOT_CANCELLABLE");
        return copy(FoodOrderStatus.CANCELLATION_PENDING, paymentStatus,
                RefundStatus.REQUESTED, paymentOrderId, now);
    }

    public FoodOrder refundProcessing(Instant now) {
        if (refundStatus == RefundStatus.PROCESSING) return this;
        require(status == FoodOrderStatus.CANCELLATION_PENDING
                        && refundStatus == RefundStatus.REQUESTED,
                "COMMERCE_REFUND_STATE_CONFLICT");
        return copy(status, paymentStatus, RefundStatus.PROCESSING, paymentOrderId, now);
    }

    public FoodOrder refundSucceeded(long authoritativeAmountCent, Instant now) {
        if (status == FoodOrderStatus.CANCELLED && refundStatus == RefundStatus.SUCCEEDED) return this;
        require(status == FoodOrderStatus.CANCELLATION_PENDING, "COMMERCE_REFUND_STATE_CONFLICT");
        require(authoritativeAmountCent == payableAmountCent, "COMMERCE_REFUND_AMOUNT_MISMATCH");
        return copy(FoodOrderStatus.CANCELLED, paymentStatus, RefundStatus.SUCCEEDED, paymentOrderId, now);
    }

    public FoodOrder refundFailed(Instant now) {
        if (status == FoodOrderStatus.REFUND_FAILED && refundStatus == RefundStatus.FAILED) return this;
        require(status == FoodOrderStatus.CANCELLATION_PENDING, "COMMERCE_REFUND_STATE_CONFLICT");
        return copy(FoodOrderStatus.REFUND_FAILED, paymentStatus, RefundStatus.FAILED, paymentOrderId, now);
    }

    public FoodOrder merchantAccepted(Instant now) {
        return advance(FoodOrderStatus.PAID, FoodOrderStatus.MERCHANT_ACCEPTED, now);
    }

    public FoodOrder preparing(Instant now) {
        return advance(FoodOrderStatus.MERCHANT_ACCEPTED, FoodOrderStatus.PREPARING, now);
    }

    public FoodOrder delivering(Instant now) {
        return advance(FoodOrderStatus.PREPARING, FoodOrderStatus.DELIVERING, now);
    }

    public FoodOrder delivered(Instant now) {
        return advance(FoodOrderStatus.DELIVERING, FoodOrderStatus.DELIVERED, now);
    }

    private FoodOrder advance(FoodOrderStatus expected, FoodOrderStatus target, Instant now) {
        if (status == target) return this;
        require(status == expected, "COMMERCE_ORDER_STATE_CONFLICT");
        return copy(target, paymentStatus, refundStatus, paymentOrderId, now);
    }

    private FoodOrder copy(
            FoodOrderStatus nextStatus,
            PaymentStatus nextPaymentStatus,
            RefundStatus nextRefundStatus,
            UUID nextPaymentOrderId,
            Instant now) {
        return new FoodOrder(id, orderNo, userId, merchantId, merchantName, addressId,
                addressSummary, quoteId, nextPaymentOrderId, itemAmountCent, deliveryFeeCent,
                discountCent, payableAmountCent, nextStatus, nextPaymentStatus,
                nextRefundStatus, expiresAt, version + 1, createdAt, now);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new CommerceDomainException(code, "外卖订单状态已变化，请刷新后重试");
    }
}
