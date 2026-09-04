package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.RealNameVerificationPort;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository.VerificationRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealNameVerificationServiceTest {
    @Test
    void persistsOnlyMaskedAndKeyedValuesAfterProviderVerification() {
        RealNameVerificationPort provider = mock(RealNameVerificationPort.class);
        RealNameVerificationRepository repository = mock(RealNameVerificationRepository.class);
        UUID userId = UUID.randomUUID();
        String key = "real-name-request-0001";
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, 2};
        when(repository.findByIdempotency(userId, key)).thenReturn(Optional.empty());
        when(provider.verify("张三", "11010519491231002X", jpeg)).thenReturn(
                new RealNameVerificationPort.VerificationResult(true, "sandbox-ref", null));
        when(repository.find(any(), any())).thenAnswer(invocation -> Optional.of(new VerificationRow(
                invocation.getArgument(1), userId, new byte[32], "张*", "110***********002X",
                "sandbox", "sandbox-ref", "VERIFIED", null, Instant.now(), Instant.now(), Instant.now())));
        RealNameVerificationService service = new RealNameVerificationService(
                provider, repository, "test-real-name-hmac-key-at-least-32-characters", "sandbox");

        RealNameVerificationService.VerificationView result = service.verify(
                userId, key, "张三", "11010519491231002X", jpeg);

        assertThat(result.status()).isEqualTo("VERIFIED");
        ArgumentCaptor<byte[]> requestHash = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> nameHash = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> idHash = ArgumentCaptor.forClass(byte[].class);
        verify(repository).insert(any(), any(), anyString(), requestHash.capture(),
                org.mockito.ArgumentMatchers.eq("张*"), nameHash.capture(),
                org.mockito.ArgumentMatchers.eq("110***********002X"), idHash.capture(),
                org.mockito.ArgumentMatchers.eq("sandbox"));
        assertThat(requestHash.getValue()).hasSize(32);
        assertThat(nameHash.getValue()).hasSize(32);
        assertThat(idHash.getValue()).hasSize(32);
    }
}
