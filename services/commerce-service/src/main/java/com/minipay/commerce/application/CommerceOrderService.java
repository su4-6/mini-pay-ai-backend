package com.minipay.commerce.application;

import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.FoodOrder;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceOrderService {
    private static final String PAYMENT_CONSUMER = "commerce-payment-result-v1";
    private final CommerceRepository repository;
    private final Clock clock;

    @Autowired
    public CommerceOrderService(CommerceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CommerceOrderService(CommerceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FoodOrder get(UUID userId, UUID orderId) {
        return repository.findOrder(userId, orderId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在或不可访问"));
    }

    @Transactional
    public FoodOrder cancel(UUID userId, UUID orderId) {
        FoodOrder before = get(userId, orderId);
        FoodOrder after = before.requestCancellation(clock.instant());
        if (after == before) return before;
        String eventType;
        Map<String, Object> payload = orderPayload(after);
        if (after.status() == FoodOrderStatus.CANCELLATION_PENDING) {
            if (after.paymentOrderId() == null) {
                throw new CommerceApplicationException(
                        "COMMERCE_PAYMENT_REFERENCE_MISSING", "支付状态正在同步，请稍后重试");
            }
            eventType = "commerce.refund.requested";
            payload.put("paymentOrderId", after.paymentOrderId());
            payload.put("amountCent", after.payableAmountCent());
        } else {
            eventType = "commerce.food-order.cancelled";
            repository.releaseReservation(after.id(), clock.instant());
        }
        save(before, after, "USER_CANCELLED", "USER", eventType, payload);
        return after;
    }

    @Transactional
    public FoodOrder paymentSucceeded(
            UUID eventId,
            UUID orderId,
            UUID paymentOrderId,
            long amountCent) {
        return consumePaymentEvent(eventId, "payment.food-order.succeeded", orderId, before -> {
            FoodOrder after = before.paymentSucceeded(paymentOrderId, amountCent, clock.instant());
            if (after == before) return before;
            repository.consumeReservation(orderId, clock.instant());
            save(before, after, "PAYMENT_SUCCEEDED", "PAYMENT",
                    "commerce.food-order.paid", orderPayload(after));
            return after;
        });
    }

    @Transactional
    public FoodOrder paymentFailed(UUID eventId, UUID orderId) {
        return consumePaymentEvent(eventId, "payment.food-order.failed", orderId, before -> {
            FoodOrder after = before.paymentFailed(clock.instant());
            if (after == before) return before;
            save(before, after, "PAYMENT_FAILED", "PAYMENT",
                    "commerce.food-order.payment-failed", orderPayload(after));
            return after;
        });
    }

    @Transactional
    public FoodOrder refundProcessing(UUID eventId, UUID orderId) {
        return consumePaymentEvent(eventId, "payment.refund.processing", orderId, before -> {
            FoodOrder after = before.refundProcessing(clock.instant());
            if (after == before) return before;
            save(before, after, "REFUND_PROCESSING", "PAYMENT",
                    "commerce.food-order.refund-processing", orderPayload(after));
            return after;
        });
    }

    @Transactional
    public FoodOrder refundSucceeded(UUID eventId, UUID orderId, long amountCent) {
        return consumePaymentEvent(eventId, "payment.refund.succeeded", orderId, before -> {
            FoodOrder after = before.refundSucceeded(amountCent, clock.instant());
            if (after == before) return before;
            save(before, after, "REFUND_SUCCEEDED", "PAYMENT",
                    "commerce.food-order.cancelled", orderPayload(after));
            return after;
        });
    }

    @Transactional
    public FoodOrder refundFailed(UUID eventId, UUID orderId) {
        return consumePaymentEvent(eventId, "payment.refund.failed", orderId, before -> {
            FoodOrder after = before.refundFailed(clock.instant());
            if (after == before) return before;
            save(before, after, "REFUND_FAILED", "PAYMENT",
                    "commerce.food-order.refund-failed", orderPayload(after));
            return after;
        });
    }

    @Transactional
    public FoodOrder advanceSandbox(UUID orderId, FoodOrderStatus target) {
        FoodOrder before = repository.findOrderById(orderId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在"));
        Instant now = clock.instant();
        FoodOrder after = switch (target) {
            case MERCHANT_ACCEPTED -> before.merchantAccepted(now);
            case PREPARING -> before.preparing(now);
            case DELIVERING -> before.delivering(now);
            case DELIVERED -> before.delivered(now);
            default -> throw new CommerceApplicationException(
                    "COMMERCE_FULFILLMENT_TARGET_INVALID", "履约目标状态无效");
        };
        save(before, after, "SANDBOX_PROGRESS", "SANDBOX",
                "commerce.food-order.status-changed", orderPayload(after));
        return after;
    }

    @Transactional
    public FoodOrder expireUnpaid(UUID orderId) {
        FoodOrder before = repository.findOrderById(orderId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在"));
        if (before.status() != FoodOrderStatus.PENDING_PAYMENT
                || before.expiresAt().isAfter(clock.instant())) return before;
        FoodOrder after = before.requestCancellation(clock.instant());
        repository.releaseReservation(orderId, clock.instant());
        save(before, after, "PAYMENT_TIMEOUT", "SCHEDULER",
                "commerce.food-order.cancelled", orderPayload(after));
        return after;
    }

    @Transactional(readOnly = true)
    public List<FoodOrder> progressCandidates(
            FoodOrderStatus status, Instant updatedBefore, int limit) {
        return repository.findOrdersForProgress(status, updatedBefore, Math.min(Math.max(limit, 1), 100));
    }

    private FoodOrder consumePaymentEvent(
            UUID eventId,
            String eventType,
            UUID orderId,
            Function<FoodOrder, FoodOrder> action) {
        if (!repository.claimInbox(eventId, PAYMENT_CONSUMER, eventType, clock.instant())) {
            return repository.findOrderById(orderId)
                    .orElseThrow(() -> new CommerceApplicationException(
                            "COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在"));
        }
        FoodOrder result = action.apply(repository.findOrderById(orderId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在")));
        repository.completeInbox(eventId, PAYMENT_CONSUMER, clock.instant());
        return result;
    }

    private void save(
            FoodOrder before,
            FoodOrder after,
            String reason,
            String source,
            String eventType,
            Map<String, Object> payload) {
        if (!repository.saveTransition(before, after, reason, source, eventType, payload)) {
            throw new CommerceApplicationException(
                    "COMMERCE_ORDER_VERSION_CONFLICT", "外卖订单已更新，请刷新后重试");
        }
    }

    private static Map<String, Object> orderPayload(FoodOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("orderNo", order.orderNo());
        payload.put("userId", order.userId());
        payload.put("status", order.status().name());
        payload.put("paymentStatus", order.paymentStatus().name());
        payload.put("refundStatus", order.refundStatus().name());
        payload.put("amountCent", order.payableAmountCent());
        payload.put("currency", "CNY");
        return payload;
    }
}
