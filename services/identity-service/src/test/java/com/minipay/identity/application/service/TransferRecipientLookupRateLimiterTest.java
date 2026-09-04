package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TransferRecipientLookupRateLimiterTest {
    @Test
    void hashesUserAndClientAddressInRedisKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        TransferRecipientLookupRateLimiter limiter = new TransferRecipientLookupRateLimiter(
                redis, "unit-test-rate-pepper", 20, 100);
        UUID userId = UUID.randomUUID();

        limiter.check(userId, "192.0.2.10");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(2)).increment(keys.capture());
        org.assertj.core.api.Assertions.assertThat(keys.getAllValues())
                .allMatch(key -> !key.contains(userId.toString()) && !key.contains("192.0.2.10"));
        verify(redis, org.mockito.Mockito.times(2)).expire(anyString(), any());
    }

    @Test
    void returnsAStableRateLimitError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(21L);
        TransferRecipientLookupRateLimiter limiter = new TransferRecipientLookupRateLimiter(
                redis, "unit-test-rate-pepper", 20, 100);

        assertThatThrownBy(() -> limiter.check(UUID.randomUUID(), "192.0.2.10"))
                .isInstanceOf(TransferRecipientLookupException.class)
                .hasMessage("RECIPIENT_LOOKUP_RATE_LIMITED");
    }
}
