package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.application.port.DashboardProjectionPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardProjectionServiceTest {
    @Mock DashboardProjectionPort projection;

    @Test
    void projectsSuccessfulPaymentOnceUsingBusinessTimezone() {
        UUID eventId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        UUID merchantId = UUID.fromString("019fb3d0-1000-7000-8000-000000000001");
        Instant now = Instant.parse("2026-08-03T03:00:00Z");
        Instant occurredAt = Instant.parse("2026-08-02T16:30:00Z");
        when(projection.markEvent(eventId, "payment.succeeded.v1", now)).thenReturn(true);
        DashboardProjectionService service = new DashboardProjectionService(
                projection, Clock.fixed(now, ZoneOffset.UTC), "Asia/Shanghai");

        assertThat(service.projectPayment(
                eventId, "payment.succeeded.v1", merchantId, occurredAt, true, 12_345)).isTrue();

        verify(projection).addPlatformPayment(LocalDate.of(2026, 8, 3), 1, 1, 12_345, now);
        verify(projection).addMerchantPayment(LocalDate.of(2026, 8, 3), merchantId, 1, 12_345, now);
    }

    @Test
    void ignoresAnAlreadyProjectedEvent() {
        UUID eventId = UUID.fromString("019fb3d0-2000-7000-8000-000000000002");
        UUID merchantId = UUID.fromString("019fb3d0-1000-7000-8000-000000000001");
        Instant now = Instant.parse("2026-08-03T03:00:00Z");
        when(projection.markEvent(eventId, "payment.failed.v1", now)).thenReturn(false);
        DashboardProjectionService service = new DashboardProjectionService(
                projection, Clock.fixed(now, ZoneOffset.UTC), "Asia/Shanghai");

        assertThat(service.projectPayment(
                eventId, "payment.failed.v1", merchantId, now, false, 12_345)).isFalse();
        verify(projection, never()).addPlatformPayment(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }
}
