package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.application.port.TransferRecipientDirectoryPort;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferRecipientLookupServiceTest {
    private static final String MOBILE = "13800138000";

    private final TransferRecipientDirectoryPort directory = mock(TransferRecipientDirectoryPort.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final TransferRecipientLookupRateLimiter rateLimiter = mock(TransferRecipientLookupRateLimiter.class);
    private final PhoneNumberService phoneNumbers = new PhoneNumberService("unit-test-phone-pepper");
    private TransferRecipientLookupService service;

    @BeforeEach
    void setUp() {
        service = new TransferRecipientLookupService(
                directory, phoneNumbers, storage, rateLimiter, Duration.ofMinutes(30));
    }

    @Test
    void returnsOnlyMaskedRecipientDataAndTemporaryAvatar() {
        UUID requester = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(directory.findByPhoneHash(any())).thenReturn(Optional.of(
                new TransferRecipientDirectoryPort.RecipientRecord(
                        recipientId, "小满", "138****8000", "avatars/recipient/a.jpg", "张*")));
        when(storage.signRead("avatars/recipient/a.jpg", Duration.ofMinutes(30)))
                .thenReturn(new ObjectStoragePort.SignedRead(
                        URI.create("https://private.example/avatar"), Instant.now().plusSeconds(1800)));

        TransferRecipientLookupService.RecipientView result =
                service.resolveMobile(requester, MOBILE, "127.0.0.1");

        assertThat(result.recipientUserId()).isEqualTo(recipientId);
        assertThat(result.nickname()).isEqualTo("小满");
        assertThat(result.phoneMasked()).isEqualTo("138****8000");
        assertThat(result.legalNameMasked()).isEqualTo("张*");
        assertThat(result.avatarUrl()).isEqualTo("https://private.example/avatar");
        assertThat(result.verified()).isTrue();
        assertThat(result.toString()).doesNotContain(MOBILE);
        verify(rateLimiter).check(requester, "127.0.0.1");
    }

    @Test
    void permitsAnUnverifiedRecipientAndFallsBackToTheDefaultAvatar() {
        UUID requester = UUID.randomUUID();
        when(directory.findByPhoneHash(any())).thenReturn(Optional.of(
                new TransferRecipientDirectoryPort.RecipientRecord(
                        UUID.randomUUID(), "未实名用户", null, "avatars/unavailable.jpg", null)));
        when(storage.signRead(eq("avatars/unavailable.jpg"), any()))
                .thenThrow(new ProfileRejectedException("AVATAR_OBJECT_UNAVAILABLE"));

        TransferRecipientLookupService.RecipientView result =
                service.resolveMobile(requester, MOBILE, "127.0.0.1");

        assertThat(result.phoneMasked()).isEqualTo("138****8000");
        assertThat(result.legalNameMasked()).isNull();
        assertThat(result.avatarUrl()).isNull();
        assertThat(result.verified()).isFalse();
    }

    @Test
    void rejectsInvalidMobileBeforeRateLimitingOrDirectoryAccess() {
        UUID requester = UUID.randomUUID();

        assertThatThrownBy(() -> service.resolveMobile(requester, "12345", "127.0.0.1"))
                .isInstanceOf(TransferRecipientLookupException.class)
                .hasMessage("MOBILE_INVALID")
                .hasMessageNotContaining("12345");

        verify(rateLimiter, never()).check(any(), any());
        verify(directory, never()).findByPhoneHash(any());
    }

    @Test
    void usesTheSameGenericNotFoundResultForAnIneligibleAccount() {
        UUID requester = UUID.randomUUID();
        when(directory.findByPhoneHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveMobile(requester, MOBILE, "127.0.0.1"))
                .isInstanceOf(TransferRecipientLookupException.class)
                .hasMessage("TRANSFER_RECIPIENT_NOT_FOUND")
                .hasMessageNotContaining(MOBILE);
    }

    @Test
    void rejectsTransfersToTheCurrentConsumer() {
        UUID requester = UUID.randomUUID();
        when(directory.findByPhoneHash(any())).thenReturn(Optional.of(
                new TransferRecipientDirectoryPort.RecipientRecord(
                        requester, "自己", "138****8000", null, null)));

        assertThatThrownBy(() -> service.resolveMobile(requester, MOBILE, "127.0.0.1"))
                .isInstanceOf(TransferRecipientLookupException.class)
                .hasMessage("SELF_TRANSFER_NOT_ALLOWED");
    }
}
