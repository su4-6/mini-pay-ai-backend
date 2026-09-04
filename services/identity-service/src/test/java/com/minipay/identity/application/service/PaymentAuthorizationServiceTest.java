package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.PaymentAuthorizationService.ConsumedAuthorization;
import com.minipay.identity.application.service.PaymentAuthorizationService.IssuedAuthorization;
import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository;
import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository.AuthorizationRow;
import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository.CredentialRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PaymentAuthorizationServiceTest {
    @Test
    void bindsTheAuthorizationToUserSubjectAndAmountAndConsumesIt() {
        PaymentAuthorizationRepository repository =
                mock(PaymentAuthorizationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AtomicReference<AuthorizationRow> stored = new AtomicReference<>();
        UUID userId = UUID.fromString("0197f000-0000-7000-8000-000000000001");
        UUID subjectId = UUID.fromString("0197f000-0000-7000-8000-000000000002");
        when(repository.findByIdempotency(userId, "authorization-0001"))
                .thenReturn(Optional.empty());
        when(repository.isRealNameVerified(userId)).thenReturn(true);
        when(repository.lockPaymentCredential(userId)).thenReturn(Optional.of(
                new CredentialRow("argon2-hash", 0, null, "ACTIVE")));
        when(encoder.matches("123456", "argon2-hash")).thenReturn(true);
        doAnswer(invocation -> {
            stored.set(new AuthorizationRow(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    invocation.getArgument(6),
                    invocation.getArgument(7),
                    invocation.getArgument(8),
                    invocation.getArgument(9),
                    invocation.getArgument(10),
                    null));
            return null;
        }).when(repository).insertAuthorization(
                any(), any(), anyString(), any(), any(), anyString(), any(),
                any(Long.class), anyString(), any(), any(Instant.class));
        when(repository.lockByTokenHash(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        PaymentAuthorizationService service = new PaymentAuthorizationService(
                repository,
                encoder,
                "test-payment-authorization-key-long-enough");

        IssuedAuthorization issued = service.issue(
                userId,
                "authorization-0001",
                "PAYMENT_ORDER",
                subjectId,
                1234L,
                "device-1",
                "123456");
        ConsumedAuthorization consumed = service.verifyAndConsume(
                issued.paymentAuthToken(),
                userId,
                "PAYMENT_ORDER",
                subjectId,
                1234L,
                "device-1");

        assertThat(consumed.valid()).isTrue();
        assertThat(consumed.authorizationId()).isEqualTo(issued.authorizationId());
        verify(repository).consume(issued.authorizationId());
    }

    @Test
    void rejectsTheSameTokenForADifferentAmount() {
        PaymentAuthorizationRepository repository =
                mock(PaymentAuthorizationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID userId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        PaymentAuthorizationService service = new PaymentAuthorizationService(
                repository,
                encoder,
                "test-payment-authorization-key-long-enough");
        AuthorizationRow row = new AuthorizationRow(
                UUID.randomUUID(),
                userId,
                "authorization-0002",
                new byte[32],
                subjectId,
                "WITHDRAWAL_ORDER",
                subjectId,
                100L,
                "device-1",
                new byte[32],
                Instant.now().plusSeconds(60),
                null);
        when(repository.lockByTokenHash(any())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.verifyAndConsume(
                "invalid-for-binding",
                userId,
                "WITHDRAWAL_ORDER",
                subjectId,
                101L,
                "device-1"))
                .isInstanceOf(PaymentAuthorizationRejectedException.class)
                .extracting("code")
                .isEqualTo("PAYMENT_AUTHORIZATION_INVALID");
        verify(repository, never()).consume(any());
    }

    @Test
    void rejectsAuthorizationIssuanceForAnUnverifiedUser() {
        PaymentAuthorizationRepository repository = mock(PaymentAuthorizationRepository.class);
        PaymentAuthorizationService service = new PaymentAuthorizationService(
                repository, mock(PasswordEncoder.class),
                "test-payment-authorization-key-long-enough");
        UUID userId = UUID.randomUUID();
        when(repository.isRealNameVerified(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(
                userId, "authorization-0003", "PAYMENT_ORDER", UUID.randomUUID(),
                100L, "device-1", "123456"))
                .isInstanceOf(PaymentAuthorizationRejectedException.class)
                .extracting("code")
                .isEqualTo("REAL_NAME_VERIFICATION_REQUIRED");
        verify(repository, never()).lockPaymentCredential(any());
    }

    @Test
    void allowsZeroAmountOnlyForABankCardBalanceQuery() {
        PaymentAuthorizationRepository repository = mock(PaymentAuthorizationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        when(repository.findByIdempotency(userId, "authorization-0004"))
                .thenReturn(Optional.empty());
        when(repository.isRealNameVerified(userId)).thenReturn(true);
        when(repository.lockPaymentCredential(userId)).thenReturn(Optional.of(
                new CredentialRow("argon2-hash", 0, null, "ACTIVE")));
        when(encoder.matches("123456", "argon2-hash")).thenReturn(true);
        PaymentAuthorizationService service = new PaymentAuthorizationService(
                repository, encoder, "test-payment-authorization-key-long-enough");

        IssuedAuthorization authorization = service.issue(
                userId,
                "authorization-0004",
                "BANK_CARD_BALANCE_QUERY",
                cardId,
                0L,
                "device-1",
                "123456");

        assertThat(authorization.paymentAuthToken()).isNotBlank();
        assertThatThrownBy(() -> service.issue(
                userId,
                "authorization-0005",
                "PAYMENT_ORDER",
                UUID.randomUUID(),
                0L,
                "device-1",
                "123456"))
                .isInstanceOf(PaymentAuthorizationRejectedException.class)
                .extracting("code")
                .isEqualTo("AMOUNT_OUT_OF_RANGE");
    }

    @Test
    void acceptsRechargeAuthorizationOnlyWithAPositiveAmount() {
        PaymentAuthorizationRepository repository = mock(PaymentAuthorizationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID userId = UUID.randomUUID();
        when(repository.findByIdempotency(userId, "authorization-recharge"))
                .thenReturn(Optional.empty());
        when(repository.isRealNameVerified(userId)).thenReturn(true);
        when(repository.lockPaymentCredential(userId)).thenReturn(Optional.of(
                new CredentialRow("argon2-hash", 0, null, "ACTIVE")));
        when(encoder.matches("123456", "argon2-hash")).thenReturn(true);
        PaymentAuthorizationService service = new PaymentAuthorizationService(
                repository, encoder, "test-payment-authorization-key-long-enough");

        IssuedAuthorization authorization = service.issue(userId, "authorization-recharge",
                "RECHARGE_ORDER", UUID.randomUUID(), 1L, "device-1", "123456");

        assertThat(authorization.paymentAuthToken()).isNotBlank();
    }
}
