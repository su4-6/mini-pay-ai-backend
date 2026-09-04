package com.minipay.identity.application.service;

import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.application.port.TransferRecipientDirectoryPort;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TransferRecipientLookupService {
    private final TransferRecipientDirectoryPort directory;
    private final PhoneNumberService phoneNumbers;
    private final ObjectStoragePort storage;
    private final TransferRecipientLookupRateLimiter rateLimiter;
    private final Duration avatarReadTtl;

    public TransferRecipientLookupService(
            TransferRecipientDirectoryPort directory,
            PhoneNumberService phoneNumbers,
            ObjectStoragePort storage,
            TransferRecipientLookupRateLimiter rateLimiter,
            @Value("${minipay.identity.profile.read-url-ttl}") Duration avatarReadTtl) {
        this.directory = directory;
        this.phoneNumbers = phoneNumbers;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.avatarReadTtl = avatarReadTtl;
    }

    public RecipientView resolveMobile(UUID requesterUserId, String rawMobile, String clientAddress) {
        String mobile;
        try {
            mobile = phoneNumbers.normalize(rawMobile);
        } catch (IllegalArgumentException exception) {
            throw new TransferRecipientLookupException("MOBILE_INVALID");
        }
        rateLimiter.check(requesterUserId, clientAddress);
        TransferRecipientDirectoryPort.RecipientRecord recipient = directory
                .findByPhoneHash(phoneNumbers.hash(mobile))
                .orElseThrow(() -> new TransferRecipientLookupException("TRANSFER_RECIPIENT_NOT_FOUND"));
        if (recipient.userId().equals(requesterUserId)) {
            throw new TransferRecipientLookupException("SELF_TRANSFER_NOT_ALLOWED");
        }

        String avatarUrl = null;
        if (recipient.avatarObjectKey() != null) {
            try {
                avatarUrl = storage.signRead(recipient.avatarObjectKey(), avatarReadTtl).url().toString();
            } catch (RuntimeException ignored) {
                // Recipient resolution remains usable with the default avatar.
            }
        }
        String phoneMasked = recipient.phoneMasked() == null || recipient.phoneMasked().isBlank()
                ? phoneNumbers.mask(mobile)
                : recipient.phoneMasked();
        return new RecipientView(
                recipient.userId(), recipient.nickname(), phoneMasked,
                recipient.legalNameMasked(), avatarUrl, recipient.legalNameMasked() != null);
    }

    public record RecipientView(
            UUID recipientUserId,
            String nickname,
            String phoneMasked,
            String legalNameMasked,
            String avatarUrl,
            boolean verified) {
    }
}
