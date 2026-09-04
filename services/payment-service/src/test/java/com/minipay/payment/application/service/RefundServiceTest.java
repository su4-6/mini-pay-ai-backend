package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.RefundRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.RefundablePaymentRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RefundServiceTest {
    private static final UUID PAYMENT_ID =
            UUID.fromString("0197f000-0000-7000-8000-000000000010");
    private static final UUID USER_ID =
            UUID.fromString("0197f000-0000-7000-8000-000000000011");

    @Test
    void postsOneFullWalletRefundAndReplaysIdempotently() {
        PaymentRepository repository = mock(PaymentRepository.class);
        WalletInternalClient wallet = mock(WalletInternalClient.class);
        DomainEventWriter events = mock(DomainEventWriter.class);
        AtomicReference<RefundRow> stored = new AtomicReference<>();
        RefundablePaymentRow payment = new RefundablePaymentRow(
                PAYMENT_ID, "P001", USER_ID, 12_345L, "CNY", "WALLET_BALANCE", "SUCCEEDED");
        when(repository.findRefundablePayment(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(repository.findRefundByRequestNo(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insertRefund(
                        any(), anyString(), any(), anyString(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    RefundRow row = new RefundRow(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            "PROCESSING",
                            null,
                            Instant.now());
                    stored.set(row);
                    return row;
                });
        doAnswer(invocation -> {
            Runnable stateChange = invocation.getArgument(0);
            stateChange.run();
            RefundRow current = stored.get();
            stored.set(new RefundRow(
                    current.refundId(),
                    current.refundNo(),
                    current.paymentOrderId(),
                    current.merchantRefundNo(),
                    current.amountCent(),
                    "SUCCEEDED",
                    null,
                    Instant.now()));
            return UUID.randomUUID();
        }).when(events).writeAfter(any(), anyString(), anyString(), any(), any());

        RefundService service = new RefundService(repository, wallet, events);
        Refund first = service.create(
                "refund-request-0001", PAYMENT_ID, 12_345L, "demo refund");
        Refund replay = service.create(
                "refund-request-0001", PAYMENT_ID, 12_345L, "demo refund");

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.refundId()).isEqualTo(first.refundId());
        verify(wallet).creditRefund(
                first.refundId(),
                RequestSupport.businessNo("R", first.refundId()),
                USER_ID,
                12_345L,
                "P001");
    }

    @Test
    void rejectsPartialRefundInP0() {
        PaymentRepository repository = mock(PaymentRepository.class);
        when(repository.findRefundablePayment(PAYMENT_ID)).thenReturn(Optional.of(
                new RefundablePaymentRow(
                        PAYMENT_ID,
                        "P001",
                        USER_ID,
                        12_345L,
                        "CNY",
                        "WALLET_BALANCE",
                        "SUCCEEDED")));
        RefundService service = new RefundService(
                repository, mock(WalletInternalClient.class), mock(DomainEventWriter.class));

        assertThatThrownBy(() -> service.create(
                "refund-request-0002", PAYMENT_ID, 1_000L, null))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("P0_REFUND_MUST_BE_FULL_AMOUNT");
    }
}
