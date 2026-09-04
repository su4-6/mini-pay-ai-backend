package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.domain.model.PaymentOrder;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantSettlementContextRow;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FoodPaymentSagaServiceTest {
    @Test
    void createsWalletPaymentOnlyFromAuthoritativeCommerceEvent() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentOrderService payments = mock(PaymentOrderService.class);
        RefundService refunds = mock(RefundService.class);
        FoodPaymentSagaService saga = new FoodPaymentSagaService(
                repository, payments, refunds, mock(MerchantRepository.class), "");
        UUID eventId = UUID.randomUUID();
        UUID foodOrderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentOrderId = UUID.randomUUID();
        PaymentOrder payment = new PaymentOrder(
                paymentOrderId, "P001", 3300, "CNY", "外卖订单 FO001",
                "WALLET_BALANCE", "PROCESSING", null, null,
                Instant.now().plusSeconds(900), Instant.now());
        when(repository.claimInbox(eventId, "payment-food-order-created-v1",
                "commerce.food-order.created")).thenReturn(true);
        when(payments.create(userId, "food:" + foodOrderId, 3300,
                "外卖订单 FO001", "WALLET_BALANCE")).thenReturn(payment);

        saga.orderCreated(eventId, foodOrderId, "FO001", userId, 3300, "CNY");

        verify(repository).linkFoodPayment(
                foodOrderId, "FO001", paymentOrderId, userId, 3300, eventId);
        verify(repository).completeInbox(eventId, "payment-food-order-created-v1");
    }

    @Test
    void rejectsNonCnyOrNonPositiveAuthorityEvent() {
        FoodPaymentSagaService saga = new FoodPaymentSagaService(
                mock(PaymentRepository.class),
                mock(PaymentOrderService.class),
                mock(RefundService.class),
                mock(MerchantRepository.class), "");
        assertThatThrownBy(() -> saga.orderCreated(
                UUID.randomUUID(), UUID.randomUUID(), "FO001", UUID.randomUUID(), 0, "CNY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> saga.orderCreated(
                UUID.randomUUID(), UUID.randomUUID(), "FO001", UUID.randomUUID(), 100, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsYshopMerchantPaymentFromConfiguredPlatformApplication() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentOrderService payments = mock(PaymentOrderService.class);
        MerchantRepository merchants = mock(MerchantRepository.class);
        FoodPaymentSagaService saga = new FoodPaymentSagaService(
                repository, payments, mock(RefundService.class), merchants, "food-app");
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID paymentOrderId = UUID.randomUUID();
        PaymentOrder payment = new PaymentOrder(
                paymentOrderId, "P002", 4280, "CNY", "外卖订单 YS002",
                "WALLET_BALANCE", "PROCESSING", null, null,
                Instant.now().plusSeconds(900), Instant.now());
        when(merchants.findMerchantSettlementContextByAppId("food-app"))
                .thenReturn(Optional.of(new MerchantSettlementContextRow(
                        merchantId, applicationId, "M001", "yshop 平台商户",
                        UUID.randomUUID(), "ACTIVE", "food-app", "ACTIVE", "WALLET")));
        when(repository.claimInbox(eventId, "payment-food-order-created-v2",
                "commerce.food-order.created")).thenReturn(true);
        when(payments.createMerchantOrder(
                merchantId, applicationId, "food-app", userId, "YS002", 4280,
                "外卖订单 YS002", "WALLET_BALANCE")).thenReturn(payment);

        saga.yshopOrderCreated(eventId, orderId, "YS002", userId, 4280, "CNY");

        verify(repository).linkFoodPayment(
                orderId, "YS002", paymentOrderId, userId, 4280, eventId);
        verify(repository).completeInbox(eventId, "payment-food-order-created-v2");
    }
}
