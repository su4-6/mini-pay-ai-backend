package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOrderExpiryJob {
    private final PaymentRepository repository;
    private final PaymentOrderService payments;

    public PaymentOrderExpiryJob(
            PaymentRepository repository,
            PaymentOrderService payments) {
        this.repository = repository;
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${minipay.payment.order-expiry-delay-ms:2000}")
    public void closeExpiredOrders() {
        repository.findExpiredProcessingPayments(Instant.now(), 100)
                .forEach(order -> payments.expirePayment(
                        order.userId(), order.paymentOrderId()));
    }
}
