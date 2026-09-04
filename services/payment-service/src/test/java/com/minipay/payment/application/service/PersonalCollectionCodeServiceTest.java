package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.domain.model.PersonalCollectionCode;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.CollectionCodeRow;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PersonalCollectionCodeServiceTest {
    @Test
    void signsStoresOnlyTheDigestAndResolvesTheOwner() {
        PaymentRepository repository = mock(PaymentRepository.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        AtomicReference<CollectionCodeRow> stored = new AtomicReference<>();
        when(repository.findCurrentCollectionCode(any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            UUID codeId = invocation.getArgument(0);
            UUID ownerId = invocation.getArgument(1);
            String nonce = invocation.getArgument(2);
            byte[] tokenHash = invocation.getArgument(3);
            Instant expiresAt = invocation.getArgument(4);
            stored.set(new CollectionCodeRow(
                    codeId,
                    ownerId,
                    nonce,
                    tokenHash,
                    "ACTIVE",
                    expiresAt));
            return null;
        }).when(repository).insertCollectionCode(
                any(), any(), anyString(), any(), any(Instant.class));
        when(repository.findCollectionCodeByHash(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        UUID ownerId = UUID.fromString("0197f000-0000-7000-8000-000000000001");
        UUID scannerId = UUID.fromString("0197f000-0000-7000-8000-000000000002");
        when(identity.consumerPaymentProfile(ownerId)).thenReturn(
                new IdentityInternalClient.ConsumerPaymentProfile(
                        ownerId, "小满", null, "张*"));

        Instant now = Instant.parse("2026-08-07T00:00:00Z");
        PersonalCollectionCodeService service = new PersonalCollectionCodeService(
                repository,
                identity,
                "test-collection-signing-key-which-is-long-enough",
                Clock.fixed(now, ZoneOffset.UTC));
        PersonalCollectionCode code = service.current(ownerId);

        assertThat(code.deepLink()).startsWith("minipay://collect/personal?token=");
        assertThat(new String(stored.get().tokenHash()))
                .doesNotContain(code.deepLink());
        var resolution = service.resolve(scannerId, code.deepLink());
        assertThat(resolution.receiverUserId()).isEqualTo(ownerId);
        verify(repository).expireCollectionCodes(ownerId, now);
        verify(repository).findCurrentCollectionCode(ownerId, now);
        assertThat(resolution.receiverDisplay()).isEqualTo("小满（张*）");
        assertThat(resolution.receiverLegalNameMasked()).isEqualTo("张*");
    }

    @Test
    void rejectsATamperedToken() {
        PaymentRepository repository = mock(PaymentRepository.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        PersonalCollectionCodeService service = new PersonalCollectionCodeService(
                repository,
                identity,
                "test-collection-signing-key-which-is-long-enough",
                Clock.systemUTC());

        assertThatThrownBy(() -> service.resolve(
                UUID.randomUUID(),
                "minipay://collect/personal?token=djE.invalid"))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("INVALID_OR_EXPIRED_COLLECTION_CODE");
    }

    @Test
    void rotatesACodeThatIsCloseToExpiry() {
        PaymentRepository repository = mock(PaymentRepository.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        UUID ownerId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        when(repository.findCurrentCollectionCode(any(), any())).thenReturn(Optional.of(
                new CollectionCodeRow(
                        codeId,
                        ownerId,
                        "nonce",
                        new byte[32],
                        "ACTIVE",
                        Instant.now().plusSeconds(30))));

        PersonalCollectionCodeService service = new PersonalCollectionCodeService(
                repository,
                identity,
                "test-collection-signing-key-which-is-long-enough",
                Clock.systemUTC());

        service.current(ownerId);

        verify(repository).revokeCollectionCode(codeId);
    }
}
