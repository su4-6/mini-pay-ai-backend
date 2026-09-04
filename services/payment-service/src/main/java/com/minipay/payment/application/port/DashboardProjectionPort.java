package com.minipay.payment.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface DashboardProjectionPort {
    boolean markEvent(UUID eventId, String eventType, Instant projectedAt);

    void addPlatformPayment(
            LocalDate date, long submittedCount, long successfulCount, long amountCent, Instant at);

    void addMerchantPayment(
            LocalDate date, UUID merchantId, long successfulCount, long amountCent, Instant at);

    void addPlatformRefund(
            LocalDate date, long successfulCount, long amountCent, Instant at);

    void addMerchantRefund(
            LocalDate date, UUID merchantId, long successfulCount, long amountCent, Instant at);
}
