package com.minipay.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentOperationalMetricsTest {

    @Test
    void staleAlertOnlyCountsAuthorizedTransfers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        PaymentOperationalMetrics metrics = new PaymentOperationalMetrics(
                jdbc, new SimpleMeterRegistry());

        metrics.refresh();

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4))
                .queryForObject(queries.capture(), eq(Long.class));
        assertThat(queries.getAllValues().get(1))
                .contains("authorization_id IS NOT NULL");
    }
}
