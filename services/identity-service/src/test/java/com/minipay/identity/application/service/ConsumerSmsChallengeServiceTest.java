package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.SmsSender;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class ConsumerSmsChallengeServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final SmsSender sender = mock(SmsSender.class);
    private final AuthRateLimitService rateLimits = mock(AuthRateLimitService.class);
    private final PhoneNumberService phoneNumbers = new PhoneNumberService("unit-phone-pepper");
    private final ConsumerSmsChallengeService service = new ConsumerSmsChallengeService(
            redis,
            phoneNumbers,
            sender,
            rateLimits,
            "unit-code-pepper",
            Duration.ofMinutes(5),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(true);
        when(sender.demoCodeForView()).thenReturn("123456");
    }

    @Test
    void storesCodeDigestAndTemporaryMobileWithExpectedExpiry() {
        ConsumerSmsChallengeService.ConsumerSmsChallenge challenge =
                service.create("13800138000", "203.0.113.7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(anyString(), fields.capture());
        assertThat(fields.getValue().get("codeDigest").toString())
                .hasSize(64)
                .doesNotContain("123456");
        assertThat(fields.getValue())
                .containsEntry("mobile", "13800138000")
                .containsEntry("attempts", "0");
        assertThat(challenge.maskedMobile()).isEqualTo("138****8000");
        assertThat(challenge.resendAfterSeconds()).isEqualTo(60);
        verify(sender).sendConsumerLoginCode("13800138000", "123456");
    }

    @Test
    void rejectsResendBeforeSixtySecondsWithoutSendingAnotherCode() {
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create("13800138000", "203.0.113.7"))
                .isInstanceOf(LoginRejectedException.class)
                .extracting("code")
                .isEqualTo("SMS_RESEND_TOO_SOON");
        verify(sender, never()).sendConsumerLoginCode(anyString(), anyString());
    }

    @Test
    void removesChallengeAndResendGuardWhenProviderFails() {
        doThrow(new RuntimeException("provider unavailable"))
                .when(sender).sendConsumerLoginCode("13800138000", "123456");

        assertThatThrownBy(() -> service.create("13800138000", "203.0.113.7"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("provider unavailable");

        verify(redis).delete(org.mockito.ArgumentMatchers.startsWith(
                "minipay:auth:consumer:challenge:"));
        verify(redis).delete(org.mockito.ArgumentMatchers.startsWith(
                "minipay:auth:consumer:resend:"));
        verify(redis).delete(org.mockito.ArgumentMatchers.startsWith(
                "minipay:auth:consumer:latest:"));
    }

    @Test
    void mapsInvalidExpiredAndLockedScriptResultsToStableCodes() {
        assertConsumeFailure("INVALID", "SMS_INVALID");
        assertConsumeFailure("EXPIRED", "SMS_EXPIRED");
        assertConsumeFailure("LOCKED", "SMS_LOCKED");
    }

    @Test
    void consumesSuccessfulChallengeExactlyAsReturnedByAtomicScript() {
        when(redis.execute(
                any(RedisScript.class),
                any(java.util.List.class),
                any(Object[].class)))
                .thenReturn("OK:13800138000");

        assertThat(service.consume("challenge", "123456").mobile())
                .isEqualTo("13800138000");
    }

    private void assertConsumeFailure(String scriptResult, String expectedCode) {
        when(redis.execute(
                any(RedisScript.class),
                any(java.util.List.class),
                any(Object[].class)))
                .thenReturn(scriptResult);

        assertThatThrownBy(() -> service.consume("challenge", "000000"))
                .isInstanceOf(LoginRejectedException.class)
                .extracting("code")
                .isEqualTo(expectedCode);
    }
}
