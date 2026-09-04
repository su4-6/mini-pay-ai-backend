package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.EmailSender;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class ConsumerAccountSecurityServiceTest {
    @Test
    void changesAPaymentPasswordOnlyForTheBoundUserAndDevice() {
        ConsumerAccountSecurityRepository accounts = mock(ConsumerAccountSecurityRepository.class);
        PaymentPasswordChangeTokenService tokens = mock(PaymentPasswordChangeTokenService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ConsumerAccountSecurityService service = service(accounts, tokens, encoder);
        UUID userId = UUID.randomUUID();
        UUID verificationId = UUID.randomUUID();
        when(tokens.verify("verification-token")).thenReturn(
                new PaymentPasswordChangeTokenService.Claims(
                        verificationId, userId, "device-1", Instant.now().plusSeconds(300)));
        when(accounts.findOperation(userId, "PAYMENT_PASSWORD_CHANGE", "change-key-000001"))
                .thenReturn(Optional.empty());
        when(encoder.encode("654321")).thenReturn("argon2-hash");

        service.changePaymentPassword(
                userId, "verification-token", "654321", "device-1", "change-key-000001");

        verify(accounts).changePaymentPassword(
                eq(verificationId), eq(userId), eq("device-1"), eq("argon2-hash"),
                eq("change-key-000001"), any(byte[].class), any(Instant.class));
    }

    @Test
    void rejectsCrossDeviceCredentialUseBeforeHashingThePassword() {
        ConsumerAccountSecurityRepository accounts = mock(ConsumerAccountSecurityRepository.class);
        PaymentPasswordChangeTokenService tokens = mock(PaymentPasswordChangeTokenService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ConsumerAccountSecurityService service = service(accounts, tokens, encoder);
        UUID userId = UUID.randomUUID();
        when(tokens.verify("verification-token")).thenReturn(
                new PaymentPasswordChangeTokenService.Claims(
                        UUID.randomUUID(), userId, "device-1", Instant.now().plusSeconds(300)));

        assertThatThrownBy(() -> service.changePaymentPassword(
                userId, "verification-token", "654321", "device-2", "change-key-000002"))
                .isInstanceOf(AccountSecurityRejectedException.class)
                .extracting("code")
                .isEqualTo("DEVICE_MISMATCH");
        verify(encoder, never()).encode(any());
        verify(accounts, never()).changePaymentPassword(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesToSendCurrentMobileChallengeWhenTheHashDoesNotMatch() {
        ConsumerAccountSecurityRepository accounts = mock(ConsumerAccountSecurityRepository.class);
        PaymentPasswordChangeTokenService tokens = mock(PaymentPasswordChangeTokenService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        PhoneNumberService phones = mock(PhoneNumberService.class);
        when(phones.normalize("13800138000")).thenReturn("13800138000");
        when(phones.hash("13800138000")).thenReturn(new byte[32]);
        ConsumerAccountSecurityService service = service(accounts, tokens, encoder, phones);
        UUID userId = UUID.randomUUID();
        when(accounts.mobileMatches(eq(userId), any(byte[].class))).thenReturn(false);

        assertThatThrownBy(() -> service.requestPaymentPasswordChallenge(
                userId, "13800138000", "device-1", "127.0.0.1", "challenge-key-001"))
                .isInstanceOf(AccountSecurityRejectedException.class)
                .extracting("code")
                .isEqualTo("CURRENT_MOBILE_MISMATCH");
    }

    private ConsumerAccountSecurityService service(
            ConsumerAccountSecurityRepository accounts,
            PaymentPasswordChangeTokenService tokens,
            PasswordEncoder encoder) {
        return service(accounts, tokens, encoder, mock(PhoneNumberService.class));
    }

    private ConsumerAccountSecurityService service(
            ConsumerAccountSecurityRepository accounts,
            PaymentPasswordChangeTokenService tokens,
            PasswordEncoder encoder,
            PhoneNumberService phones) {
        return new ConsumerAccountSecurityService(
                accounts,
                mock(ConsumerSmsChallengeService.class),
                phones,
                mock(EmailAddressService.class),
                mock(EmailSender.class),
                mock(StringRedisTemplate.class),
                tokens,
                encoder,
                new PhoneDisclosureCipher(
                        "test-phone-disclosure-key-at-least-32-characters", "test"),
                "test-account-security-pepper",
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                Duration.ofMinutes(5));
    }
}
