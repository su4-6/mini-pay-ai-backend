package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.minipay.payment.application.port.DashboardQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardQueryServiceTest {
    @Mock DashboardQueryPort dashboard;

    @Test
    void calculatesSuccessRateAndFillsMissingTrendDays() {
        Instant now = Instant.parse("2026-08-03T02:00:00Z");
        LocalDate from = LocalDate.of(2026, 7, 28);
        LocalDate to = LocalDate.of(2026, 8, 3);
        when(dashboard.aggregate(from, to)).thenReturn(
                new DashboardQueryPort.DashboardAggregate(200, 190, 123_45, 3, 500, now));
        when(dashboard.trend(from, to)).thenReturn(List.of(
                new DashboardQueryPort.DailyMetric(to, 20, 19, 123_45, 1, 100)));
        when(dashboard.activeMerchantCount(from, to)).thenReturn(8L);
        when(dashboard.pending(now.minus(Duration.ofMinutes(15)))).thenReturn(
                new DashboardQueryPort.PendingCounts(1, 2, 3, 4));
        DashboardQueryService service = new DashboardQueryService(
                dashboard, Clock.fixed(now, ZoneOffset.UTC), "Asia/Shanghai", Duration.ofMinutes(15));

        DashboardQueryService.DashboardResponse response = service.get("7d");

        assertThat(response.summary().successRateBasisPoints()).isEqualTo(9500);
        assertThat(response.summary().activeMerchantCount()).isEqualTo(8);
        assertThat(response.trend()).hasSize(7);
        assertThat(response.trend().getFirst().paymentAmountCent()).isZero();
        assertThat(response.trend().getLast().paymentAmountCent()).isEqualTo(123_45);
    }

    @Test
    void returnsZeroRateWhenNothingWasSubmitted() {
        Instant now = Instant.parse("2026-08-03T02:00:00Z");
        LocalDate from = LocalDate.of(2026, 7, 5);
        LocalDate to = LocalDate.of(2026, 8, 3);
        when(dashboard.aggregate(from, to)).thenReturn(
                new DashboardQueryPort.DashboardAggregate(0, 0, 0, 0, 0, null));
        when(dashboard.trend(from, to)).thenReturn(List.of());
        when(dashboard.activeMerchantCount(from, to)).thenReturn(0L);
        when(dashboard.pending(now.minus(Duration.ofMinutes(15)))).thenReturn(
                new DashboardQueryPort.PendingCounts(0, 0, 0, 0));
        DashboardQueryService service = new DashboardQueryService(
                dashboard, Clock.fixed(now, ZoneOffset.UTC), "Asia/Shanghai", Duration.ofMinutes(15));

        assertThat(service.get("30d").summary().successRateBasisPoints()).isZero();
    }
}
