package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.ConsumerOnboardingService.CompletionResult;
import com.minipay.identity.domain.model.ConsumerProfile;
import com.minipay.identity.infrastructure.persistence.ConsumerOnboardingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsumerOnboardingServiceTest {
    private final ConsumerOnboardingRepository repository =
            org.mockito.Mockito.mock(ConsumerOnboardingRepository.class);
    private final ConsumerProfileService profiles =
            org.mockito.Mockito.mock(ConsumerProfileService.class);
    private ConsumerOnboardingService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new ConsumerOnboardingService(repository, profiles);
        userId = UUID.randomUUID();
        when(profiles.normalizeNickname("Mini User")).thenReturn("Mini User");
        when(profiles.validateNicknameForUse("Mini User")).thenReturn("Mini User");
        when(repository.findReplay(eq(userId), any(), any(byte[].class)))
                .thenReturn(Optional.empty());
        when(repository.complete(eq(userId), any(), any(byte[].class), eq("Mini User"),
                eq(null)))
                .thenReturn(new CompletionResult(
                        new ConsumerProfile(userId, "Mini User", null, false, true), true));
    }

    @Test
    void initializationOnlyFingerprintsProfileDataAndLeavesPasswordUnset() {
        CompletionResult result = service.complete(
                userId, "idempotency-key-0001", "Mini User", null);

        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(repository).findReplay(eq(userId), eq("idempotency-key-0001"), hash.capture());
        assertThat(hash.getValue()).hasSize(32);
        assertThat(result.profile().payPasswordSet()).isFalse();
    }

    @Test
    void completedIdempotentReplaySkipsProfileValidationAndPersistence() {
        CompletionResult replay = new CompletionResult(
                new ConsumerProfile(userId, "Mini User", null, false, true), false);
        when(repository.findReplay(eq(userId), eq("idempotency-key-0001"), any(byte[].class)))
                .thenReturn(Optional.of(replay));

        CompletionResult result = service.complete(
                userId, "idempotency-key-0001", "Mini User", null);

        assertThat(result).isSameAs(replay);
        verify(profiles, never()).validateNicknameForUse(any());
        verify(profiles, never()).validateAvatarForOnboarding(any(), any());
        verify(repository, never()).complete(any(), any(), any(), any(), any());
    }
}
