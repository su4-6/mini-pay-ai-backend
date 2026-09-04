package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.PaymentOrder;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantSettlementContextRow;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoodPaymentSagaService {
    private static final String CONSUMER = "payment-food-order-created-v1";
    private final PaymentRepository repository;
    private final PaymentOrderService payments;
    private final RefundService refunds;
    private final MerchantRepository merchants;
    private final String foodPlatformAppId;

    public FoodPaymentSagaService(
            PaymentRepository repository,
            PaymentOrderService payments,
            RefundService refunds,
            MerchantRepository merchants,
            @Value("${minipay.payment.food-platform-merchant-app-id:}") String foodPlatformAppId) {
        this.repository = repository;
        this.payments = payments;
        this.refunds = refunds;
        this.merchants = merchants;
        this.foodPlatformAppId = foodPlatformAppId == null ? "" : foodPlatformAppId.strip();
    }

    @Transactional
    public PaymentOrder yshopOrderCreated(
            UUID eventId,
            UUID foodOrderId,
            String externalOrderNo,
            UUID userId,
            long amountCent,
            String currency) {
        if (!"CNY".equals(currency) || amountCent <= 0) {
            throw new IllegalArgumentException("Invalid authoritative food order amount");
        }
        if (foodPlatformAppId.isBlank()) {
            throw new IllegalStateException("FOOD_PLATFORM_MERCHANT_APP_ID is not configured");
        }
        MerchantSettlementContextRow platform = merchants
                .findMerchantSettlementContextByAppId(foodPlatformAppId)
                .filter(context -> "ACTIVE".equals(context.merchantStatus()))
                .filter(context -> "ACTIVE".equals(context.applicationStatus()))
                .filter(context -> java.util.Arrays.stream(context.availableChannels().split(","))
                        .map(String::strip).anyMatch("WALLET"::equals))
                .orElseThrow(() -> new IllegalStateException(
                        "Configured food platform merchant application is unavailable"));
        String consumer = "payment-food-order-created-v2";
        if (!repository.claimInbox(eventId, consumer, "commerce.food-order.created")) {
            return payments.getFoodPayment(userId, foodOrderId);
        }
        PaymentOrder payment = payments.createMerchantOrder(
                platform.merchantId(), platform.applicationId(), platform.appId(),
                userId, externalOrderNo, amountCent,
                "外卖订单 " + externalOrderNo, "WALLET_BALANCE");
        repository.linkFoodPayment(foodOrderId, externalOrderNo, payment.paymentOrderId(),
                userId, amountCent, eventId);
        repository.completeInbox(eventId, consumer);
        return payment;
    }

    @Transactional
    public PaymentOrder orderCreated(
            UUID eventId,
            UUID foodOrderId,
            String foodOrderNo,
            UUID userId,
            long amountCent,
            String currency) {
        if (!"CNY".equals(currency) || amountCent <= 0) {
            throw new IllegalArgumentException("Invalid authoritative food order amount");
        }
        if (!repository.claimInbox(eventId, CONSUMER, "commerce.food-order.created")) {
            return payments.getFoodPayment(userId, foodOrderId);
        }
        PaymentOrder payment = payments.create(
                userId,
                "food:" + foodOrderId,
                amountCent,
                "外卖订单 " + foodOrderNo,
                "WALLET_BALANCE");
        repository.linkFoodPayment(foodOrderId, foodOrderNo, payment.paymentOrderId(),
                userId, amountCent, eventId);
        repository.completeInbox(eventId, CONSUMER);
        return payment;
    }

    public void refundRequested(
            UUID eventId,
            UUID foodOrderId,
            UUID paymentOrderId,
            long amountCent) {
        String consumer = "payment-food-refund-requested-v1";
        if (!repository.claimInbox(eventId, consumer, "commerce.refund.requested")) return;
        UUID linkedFoodOrder = repository.findFoodOrderId(paymentOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Food payment reference not found"));
        if (!linkedFoodOrder.equals(foodOrderId)) {
            throw new IllegalArgumentException("Food payment reference mismatch");
        }
        refunds.create("food-refund:" + foodOrderId, paymentOrderId, amountCent,
                "用户取消未接单外卖订单");
        repository.completeInbox(eventId, consumer);
    }
}
