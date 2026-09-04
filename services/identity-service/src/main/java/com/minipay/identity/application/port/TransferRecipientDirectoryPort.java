package com.minipay.identity.application.port;

import java.util.Optional;
import java.util.UUID;

public interface TransferRecipientDirectoryPort {
    Optional<RecipientRecord> findByPhoneHash(byte[] phoneHash);

    record RecipientRecord(
            UUID userId,
            String nickname,
            String phoneMasked,
            String avatarObjectKey,
            String legalNameMasked) {
    }
}
