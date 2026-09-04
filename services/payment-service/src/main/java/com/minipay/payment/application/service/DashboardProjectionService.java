package com.minipay.payment.application.service;

import com.minipay.payment.application.port.DashboardProjectionPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardProjectionService {
    private final DashboardProjectionPort projection;
    private final Clock clock;
    private final ZoneId zoneId;

    public DashboardProjectionService(
            DashboardProjectionPort projection,
            Clock clock,
            @Value("${minipay.ops.dashboard-zone:Asia/Shanghai}") String zone) {
        this.projection = projection;
        this.clock = clock;
        this.zoneId = ZoneId.of(zone);
    }

    @Transactional
    public boolean projectPayment(
            UUID eventId,
            String eventType,
            UUID merchantId,
            Instant occurredAt,
            boolean succeeded,
            long amountCent) {
        Instant now = clock.instant();
        if (!projection.markEvent(eventId, eventType, now)) {
            return false;
        }
        long successCount = succeeded ? 1 : 0;
        long successfulAmount = succeeded ? amountCent : 0;
        var date = occurredAt.atZone(zoneId).toLocalDate();
        projection.addPlatformPayment(date, 1, successCount, successfulAmount, now);
        projection.addMerchantPayment(date, merchantId, successCount, successfulAmount, now);
        return true;
    }

    @Transactional
    public boolean projectRefund(
            UUID eventId,
            String eventType,
            UUID merchantId,
            Instant occurredAt,
            boolean succeeded,
            long amountCent) {
        Instant now = clock.instant();
        if (!projection.markEvent(eventId, eventType, now)) {
            return false;
        }
        long successCount = succeeded ? 1 : 0;
        long successfulAmount = succeeded ? amountCent : 0;
        var date = occurredAt.atZone(zoneId).toLocalDate();
        projection.addPlatformRefund(date, successCount, successfulAmount, now);
        projection.addMerchantRefund(date, merchantId, successCount, successfulAmount, now);
        return true;
    }
}
