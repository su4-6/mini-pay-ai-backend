package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantSettlementContextRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.PaymentOrderRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentOrderServiceTest {
    @Test
    void postsMerchantWalletPaymentWithoutScanResolution() {
        PaymentRepository repository = mock(PaymentRepository.class);
        MerchantRepository merchants = mock(MerchantRepository.class);
        WalletInternalClient wallet = mock(WalletInternalClient.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        DomainEventWriter events = mock(DomainEventWriter.class);
        PaymentOrderService service = new PaymentOrderService(
                repository, merchants, wallet, identity, events);
        UUID payer = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentOrderRow processing = row(
                orderId, payer, merchant, application, null,
                "PROCESSING", Instant.now().plusSeconds(900));
        PaymentOrderRow succeeded = row(
                orderId, payer, merchant, application, null,
                "SUCCEEDED", processing.expiresAt());
        when(repository.findPayment(payer, orderId))
                .thenReturn(Optional.of(processing), Optional.of(succeeded));
        when(identity.verifyAndConsume(
                "auth-token", payer, "PAYMENT_ORDER", orderId, 2580, "device-1"))
                .thenReturn(UUID.randomUUID());
        when(repository.paymentAnnualLimitMode(orderId)).thenReturn("LIMITED");
        when(merchants.findMerchantSettlementContext(merchant, application))
                .thenReturn(Optional.of(new MerchantSettlementContextRow(
                        merchant, application, "M001", "yshop 平台商户", owner,
                        "ACTIVE", "food-app", "ACTIVE", "WALLET")));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return UUID.randomUUID();
        }).when(events).writeAfter(
                any(Runnable.class), eq("payment.order.succeeded"),
                eq("PAYMENT_ORDER"), eq(orderId), any());

        service.confirmWalletBalance(payer, orderId, "auth-token", "device-1");

        verify(wallet).postMerchantPayment(
                orderId, "P001", payer, owner, 2580, "外卖订单 YS001",
                "LIMITED", "WALLET_BALANCE", "yshop 平台商户");
        verify(merchants).findMerchantSettlementContext(merchant, application);
        verify(merchants, never()).lockPaymentResolution(any());
    }

    @Test
    void closesExpiredOrderBeforeConsumingPaymentAuthorization() {
        PaymentRepository repository = mock(PaymentRepository.class);
        MerchantRepository merchants = mock(MerchantRepository.class);
        WalletInternalClient wallet = mock(WalletInternalClient.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        DomainEventWriter events = mock(DomainEventWriter.class);
        PaymentOrderService service = new PaymentOrderService(
                repository, merchants, wallet, identity, events);
        UUID payer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentOrderRow expired = row(
                orderId, payer, null, null, null,
                "PROCESSING", Instant.now().minusSeconds(1));
        when(repository.findPayment(payer, orderId)).thenReturn(Optional.of(expired));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return UUID.randomUUID();
        }).when(events).writeAfter(
                any(Runnable.class), eq("payment.order.closed"),
                eq("PAYMENT_ORDER"), eq(orderId), any());

        assertThatThrownBy(() -> service.confirmWalletBalance(
                payer, orderId, "auth-token", "device-1"))
                .isInstanceOf(PaymentProblemException.class)
                .hasMessageContaining("PAYMENT_ORDER_EXPIRED");

        verify(repository).completePaymentOrder(
                orderId, "CLOSED", null, "PAYMENT_ORDER_EXPIRED");
        verify(identity, never()).verifyAndConsume(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verify(wallet, never()).debitPayment(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    private static PaymentOrderRow row(
            UUID orderId,
            UUID payer,
            UUID merchant,
            UUID application,
            UUID resolution,
            String status,
            Instant expiresAt) {
        return new PaymentOrderRow(
                orderId, "P001", payer, null, null, "food:YS001",
                2580, "CNY", "外卖订单 YS001", "WALLET_BALANCE", status,
                null, null, expiresAt, Instant.now(), merchant, application,
                merchant == null ? "consumer-sandbox" : "food-app", "YS001", resolution);
    }
}
