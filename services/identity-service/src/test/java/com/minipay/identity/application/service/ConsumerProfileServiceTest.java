package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.ContentSafetyPort;
import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.AvatarUpload;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.ProfileRow;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumerProfileServiceTest {
    private final ConsumerProfileRepository repository = mock(ConsumerProfileRepository.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final ContentSafetyPort safety = mock(ContentSafetyPort.class);
    private final RealNameVerificationRepository realNameVerifications = mock(RealNameVerificationRepository.class);
    private ConsumerProfileService service;

    @BeforeEach
    void setUp() {
        service = new ConsumerProfileService(
                repository, realNameVerifications, storage, safety,
                Duration.ofMinutes(10), Duration.ofMinutes(30), 5_242_880);
    }

    @Test
    void rejectsNicknameOutsideProductCharacterSetBeforeModeration() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(userId, "a!", null, 0))
                .isInstanceOf(ProfileRejectedException.class)
                .hasMessage("NICKNAME_INVALID");

        verify(safety, never()).isNicknameAllowed("a!");
    }

    @Test
    void rejectsAvatarUploadOwnedByAnotherConsumer() {
        UUID userId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        when(safety.isNicknameAllowed("小满")).thenReturn(true);
        when(repository.findUpload(uploadId)).thenReturn(Optional.of(new AvatarUpload(
                uploadId, UUID.randomUUID(), "avatars/other/a.jpg", "image/jpeg", 10,
                "a".repeat(64), "PENDING", Instant.now().plusSeconds(60))));

        assertThatThrownBy(() -> service.update(userId, "小满", uploadId, 0))
                .isInstanceOf(ProfileRejectedException.class)
                .hasMessage("AVATAR_UPLOAD_FORBIDDEN");
    }

    @Test
    void createsUserBoundUploadGrantWithoutAcceptingObjectKey() {
        UUID userId = UUID.randomUUID();
        when(repository.find(userId)).thenReturn(Optional.of(
                new ProfileRow(userId, "小满", "MP001", null, 0)));
        when(storage.signUpload(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("image/jpeg"),
                org.mockito.ArgumentMatchers.eq("b".repeat(64)),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10))))
                .thenReturn(new ObjectStoragePort.SignedUpload(
                        java.net.URI.create("https://private.example/upload"),
                        Map.of("Content-Type", "image/jpeg"), Instant.now().plusSeconds(600)));

        service.createUpload(userId, "image/jpeg", 100, "b".repeat(64));

        verify(repository).insertUpload(org.mockito.ArgumentMatchers.argThat(upload ->
                upload.userId().equals(userId)
                        && upload.objectKey().startsWith("avatars/" + userId + "/")));
    }

    @Test
    void exposesOnlyTheExistingMaskedLegalNameOnTheOwnersProfile() {
        UUID userId = UUID.randomUUID();
        when(repository.find(userId)).thenReturn(Optional.of(
                new ProfileRow(userId, "\u5c0f\u6ee1", "MP001", null, 3)));
        when(realNameVerifications.findLatestVerified(userId)).thenReturn(Optional.of(
                new RealNameVerificationRepository.VerificationRow(
                        UUID.randomUUID(), userId, new byte[32], "\u5f20*", "110***********1234",
                        "SANDBOX", "reference", "VERIFIED", null, Instant.now(), Instant.now(), Instant.now())));

        ConsumerProfileService.ProfileView profile = service.get(userId);

        assertThat(profile.legalNameMasked()).isEqualTo("\u5f20*");
    }
}
