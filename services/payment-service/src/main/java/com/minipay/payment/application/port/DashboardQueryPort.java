package com.minipay.payment.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface DashboardQueryPort {
    DashboardAggregate aggregate(LocalDate from, LocalDate to);

    List<DailyMetric> trend(LocalDate from, LocalDate to);

    long activeMerchantCount(LocalDate from, LocalDate to);

    PendingCounts pending(Instant threshold);

    record DashboardAggregate(
            long submittedPaymentCount,
            long successfulPaymentCount,
            long paymentAmountCent,
            long successfulRefundCount,
            long refundAmountCent,
            Instant dataAsOf) {
    }

    record DailyMetric(
            LocalDate date,
            long submittedPaymentCount,
            long successfulPaymentCount,
            long paymentAmountCent,
            long successfulRefundCount,
            long refundAmountCent) {
    }

    record PendingCounts(
            long abnormalPaymentCount,
            long abnormalRefundCount,
            long abnormalTransferCount,
            long failedNotificationCount) {
    }
}
