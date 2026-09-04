package com.minipay.payment.application.service;

import com.minipay.payment.application.port.DashboardQueryPort;
import com.minipay.payment.application.port.DashboardQueryPort.DailyMetric;
import com.minipay.payment.application.port.DashboardQueryPort.PendingCounts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardQueryService {
    private final DashboardQueryPort dashboard;
    private final Clock clock;
    private final ZoneId zoneId;
    private final Duration pendingThreshold;

    public DashboardQueryService(
            DashboardQueryPort dashboard,
            Clock clock,
            @Value("${minipay.ops.dashboard-zone:Asia/Shanghai}") String zone,
            @Value("${minipay.ops.pending-threshold:PT15M}") Duration pendingThreshold) {
        this.dashboard = dashboard;
        this.clock = clock;
        this.zoneId = ZoneId.of(zone);
        this.pendingThreshold = pendingThreshold;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(String range) {
        int days = switch (range) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> throw new OpsBusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_DASHBOARD_RANGE", "range must be 7d or 30d");
        };
        LocalDate to = LocalDate.now(clock.withZone(zoneId));
        LocalDate from = to.minusDays(days - 1L);
        DashboardQueryPort.DashboardAggregate aggregate = dashboard.aggregate(from, to);
        long successRateBasisPoints = aggregate.submittedPaymentCount() == 0
                ? 0
                : Math.round(aggregate.successfulPaymentCount() * 10_000.0
                        / aggregate.submittedPaymentCount());
        Map<LocalDate, DailyMetric> byDate = new LinkedHashMap<>();
        dashboard.trend(from, to).forEach(metric -> byDate.put(metric.date(), metric));
        List<DailyMetric> trend = new ArrayList<>(days);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            trend.add(byDate.getOrDefault(date,
                    new DailyMetric(date, 0, 0, 0, 0, 0)));
        }
        PendingCounts pending = dashboard.pending(clock.instant().minus(pendingThreshold));
        Instant dataAsOf = aggregate.dataAsOf() == null ? clock.instant() : aggregate.dataAsOf();
        return new DashboardResponse(
                range, zoneId.getId(), from, to, dataAsOf,
                new DashboardSummary(
                        aggregate.paymentAmountCent(),
                        aggregate.successfulPaymentCount(),
                        successRateBasisPoints,
                        aggregate.refundAmountCent(),
                        dashboard.activeMerchantCount(from, to)),
                trend,
                pending);
    }

    public record DashboardResponse(
            String range,
            String timezone,
            LocalDate from,
            LocalDate to,
            Instant dataAsOf,
            DashboardSummary summary,
            List<DailyMetric> trend,
            PendingCounts pending) {
    }

    public record DashboardSummary(
            long paymentAmountCent,
            long paymentCount,
            long successRateBasisPoints,
            long refundAmountCent,
            long activeMerchantCount) {
    }
}
