package com.minipay.identity.application.service;

import com.minipay.identity.application.port.ContentSafetyPort;
import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.AvatarUpload;
import com.minipay.identity.infrastructure.persistence.ConsumerProfileRepository.ProfileRow;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConsumerProfileService {
    private static final Set<String> CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private final ConsumerProfileRepository repository;
    private final RealNameVerificationRepository realNameVerifications;
    private final ObjectStoragePort storage;
    private final ContentSafetyPort contentSafety;
    private final Duration uploadTtl;
    private final Duration readTtl;
    private final long maxBytes;

    public ConsumerProfileService(
            ConsumerProfileRepository repository,
            RealNameVerificationRepository realNameVerifications,
            ObjectStoragePort storage,
            ContentSafetyPort contentSafety,
            @Value("${minipay.identity.profile.upload-ttl}") Duration uploadTtl,
            @Value("${minipay.identity.profile.read-url-ttl}") Duration readTtl,
            @Value("${minipay.identity.profile.max-avatar-bytes}") long maxBytes) {
        this.repository = repository;
        this.realNameVerifications = realNameVerifications;
        this.storage = storage;
        this.contentSafety = contentSafety;
        this.uploadTtl = uploadTtl;
        this.readTtl = readTtl;
        this.maxBytes = maxBytes;
    }

    public ProfileView get(UUID userId) {
        return view(repository.find(userId)
                .orElseThrow(() -> new ProfileRejectedException("CONSUMER_NOT_FOUND")));
    }

    public UploadGrant createUpload(UUID userId, String contentType, long size, String sha256) {
        if (!CONTENT_TYPES.contains(contentType) || size <= 0 || size > maxBytes
                || sha256 == null || !sha256.matches("^[a-fA-F0-9]{64}$")) {
            throw new ProfileRejectedException("AVATAR_UPLOAD_INVALID");
        }
        if (repository.find(userId).isEmpty()) {
            throw new ProfileRejectedException("CONSUMER_NOT_FOUND");
        }
        UUID uploadId = UuidV7.generate();
        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String key = "avatars/" + userId + "/" + uploadId + "." + extension;
        String normalizedSha = sha256.toLowerCase(Locale.ROOT);
        Instant expiresAt = Instant.now().plus(uploadTtl);
        repository.insertUpload(new AvatarUpload(
                uploadId, userId, key, contentType, size, normalizedSha, "PENDING", expiresAt));
        ObjectStoragePort.SignedUpload signed =
                storage.signUpload(key, contentType, normalizedSha, uploadTtl);
        return new UploadGrant(uploadId, signed.url().toString(), signed.requiredHeaders(), signed.expiresAt());
    }

    public ProfileView update(UUID userId, String nickname, UUID uploadId, long version) {
        String normalized = validateNicknameForUse(nickname);
        AvatarUpload upload = validateAvatarForUse(userId, uploadId);
        return view(repository.update(userId, normalized, upload, version));
    }

    public String validateNicknameForUse(String nickname) {
        String normalized = normalizeNickname(nickname);
        if (!contentSafety.isNicknameAllowed(normalized)) {
            throw new ProfileRejectedException("PROFILE_CONTENT_REJECTED");
        }
        return normalized;
    }

    public String normalizeNickname(String nickname) {
        String normalized = nickname == null ? "" : nickname.strip();
        validateNickname(normalized);
        return normalized;
    }

    public AvatarUpload validateAvatarForUse(UUID userId, UUID uploadId) {
        return validateAvatar(userId, uploadId, false);
    }

    public AvatarUpload validateAvatarForOnboarding(UUID userId, UUID uploadId) {
        return validateAvatar(userId, uploadId, true);
    }

    private AvatarUpload validateAvatar(UUID userId, UUID uploadId, boolean allowConsumedReplay) {
        if (uploadId == null) {
            return null;
        }
        AvatarUpload upload = repository.findUpload(uploadId)
                .orElseThrow(() -> new ProfileRejectedException("AVATAR_UPLOAD_NOT_FOUND"));
        if (!upload.userId().equals(userId)) {
            throw new ProfileRejectedException("AVATAR_UPLOAD_FORBIDDEN");
        }
        boolean usableStatus = "PENDING".equals(upload.status())
                || (allowConsumedReplay && "CONSUMED".equals(upload.status()));
        if (!usableStatus || ("PENDING".equals(upload.status())
                && upload.expiresAt().isBefore(Instant.now()))) {
            throw new ProfileRejectedException("AVATAR_UPLOAD_EXPIRED");
        }
        ObjectStoragePort.StoredObject object = storage.head(upload.objectKey());
        if (object.size() != upload.size()
                || !upload.contentType().equalsIgnoreCase(object.contentType())
                || object.sha256() == null
                || !upload.sha256().equalsIgnoreCase(object.sha256())) {
            repository.rejectUpload(upload.uploadId());
            throw new ProfileRejectedException("AVATAR_OBJECT_MISMATCH");
        }
        if (!contentSafety.isImageAllowed(storage.signRead(upload.objectKey(), readTtl).url())) {
            repository.rejectUpload(upload.uploadId());
            throw new ProfileRejectedException("PROFILE_CONTENT_REJECTED");
        }
        return upload;
    }

    @Scheduled(fixedDelayString = "${AVATAR_CLEANUP_INTERVAL_MS:300000}")
    public void cleanupUploads() {
        repository.cleanupCandidates(100).forEach(upload -> {
            try {
                storage.delete(upload.objectKey());
                repository.markDeleted(upload.uploadId());
            } catch (RuntimeException exception) {
                repository.rescheduleCleanup(upload.uploadId());
            }
        });
    }

    private ProfileView view(ProfileRow row) {
        String avatarUrl = null;
        Instant avatarExpiry = null;
        if (row.avatarObjectKey() != null) {
            try {
                ObjectStoragePort.SignedRead signed = storage.signRead(row.avatarObjectKey(), readTtl);
                avatarUrl = signed.url().toString();
                avatarExpiry = signed.expiresAt();
            } catch (ProfileRejectedException ignored) {
                // Default avatar is safer than leaking storage configuration failures to the profile page.
            }
        }
        return new ProfileView(
                row.userId(), row.nickname(), row.minipayNo(), avatarUrl, avatarExpiry, row.version(),
                realNameVerifications.findLatestVerified(row.userId())
                        .map(RealNameVerificationRepository.VerificationRow::legalNameMasked)
                        .orElse(null));
    }

    private void validateNickname(String nickname) {
        int length = nickname.codePointCount(0, nickname.length());
        boolean validCharacters = nickname.codePoints().allMatch(codePoint ->
                codePoint == '_' || Character.isLetterOrDigit(codePoint)
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        if (length < 2 || length > 20 || !validCharacters) {
            throw new ProfileRejectedException("NICKNAME_INVALID");
        }
    }

    public record ProfileView(
            UUID userId, String nickname, String miniPayNo, String avatarUrl,
            Instant avatarUrlExpiresAt, long version, String legalNameMasked) {
    }

    public record UploadGrant(
            UUID uploadId, String uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {
    }
}
