package com.minipay.identity.application.service;

import com.minipay.identity.domain.model.ConsumerProfile;
import com.minipay.identity.infrastructure.persistence.ConsumerOnboardingRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.AvatarUpload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConsumerOnboardingService {
    private final ConsumerOnboardingRepository repository;
    private final ConsumerProfileService profiles;

    public ConsumerOnboardingService(
            ConsumerOnboardingRepository repository,
            ConsumerProfileService profiles) {
        this.repository = repository;
        this.profiles = profiles;
    }

    public CompletionResult complete(
            UUID userId,
            String idempotencyKey,
            String nickname,
            UUID avatarUploadId) {
        String normalizedNickname = profiles.normalizeNickname(nickname);
        // Never persist a verifier derived from the six-digit payment password.
        byte[] requestHash = sha256(normalizedNickname + "\u0000"
                + (avatarUploadId == null ? "" : avatarUploadId));
        CompletionResult replay = repository.findReplay(userId, idempotencyKey, requestHash)
                .orElse(null);
        if (replay != null) {
            return replay;
        }
        normalizedNickname = profiles.validateNicknameForUse(normalizedNickname);
        AvatarUpload avatar = profiles.validateAvatarForOnboarding(userId, avatarUploadId);
        return repository.complete(
                userId,
                idempotencyKey,
                requestHash,
                normalizedNickname,
                avatar);
    }


    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record CompletionResult(ConsumerProfile profile, boolean created) {
    }
}
