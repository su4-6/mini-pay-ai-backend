package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.SmsSender;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository.AdminAccount;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SmsChallengeServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AdminAuthenticationService authentication = mock(AdminAuthenticationService.class);
    private final CaptchaService captchas = mock(CaptchaService.class);
    private final PhoneNumberService phoneNumbers = new PhoneNumberService("unit-test-phone-pepper");
    private final AtomicReference<String> storedChallenge = new AtomicReference<>();
    private final SmsChallengeService service = new SmsChallengeService(
            redis,
            phoneNumbers,
            authentication,
            new FixedCodeSmsSender(),
            captchas,
            "unit-test-code-pepper",
            Duration.ofMinutes(5),
            Duration.ofSeconds(60));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        doNothing().when(captchas).consume(anyString(), anyString());
        when(authentication.eligibleSmsAccount("13800138000")).thenReturn(Optional.of(new AdminAccount(
                UUID.randomUUID(),
                "Test Admin",
                "not-used",
                "ACTIVE",
                "ACTIVE",
                0,
                null,
                true)));
        doNothing().when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void storesOnlyDigestsAndConsumesChallengeOnce() {
        when(values.getAndDelete(anyString())).thenAnswer(invocation -> storedChallenge.getAndSet(null));

        SmsChallengeService.SmsChallenge challenge =
                service.create("13800138000", "captcha-id", "ABCD");

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values).set(
                org.mockito.ArgumentMatchers.startsWith("minipay:auth:sms:"),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        storedChallenge.set(valueCaptor.getValue());

        assertThat(storedChallenge.get())
                .doesNotContain("123456")
                .doesNotContain("13800138000");
        service.consume(challenge.challengeId(), "13800138000", "123456");
        assertThatThrownBy(() -> service.consume(challenge.challengeId(), "13800138000", "123456"))
                .isInstanceOf(LoginRejectedException.class);
    }

    private static final class FixedCodeSmsSender implements SmsSender {
        @Override
        public void sendLoginCode(String normalizedPhone, String code) {
            // No-op: credentials must never be logged by an SMS adapter.
        }

        @Override
        public String demoCodeForView() {
            return "123456";
        }
    }
}
