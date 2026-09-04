package com.minipay.identity.application.service;

import com.minipay.identity.application.port.RealNameVerificationPort;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository;
import com.minipay.identity.infrastructure.persistence.RealNameVerificationRepository.VerificationRow;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RealNameVerificationService {
    private final RealNameVerificationPort provider;
    private final RealNameVerificationRepository repository;
    private final byte[] hmacKey;
    private final String providerName;

    public RealNameVerificationService(
            RealNameVerificationPort provider,
            RealNameVerificationRepository repository,
            @Value("${minipay.identity.real-name.hmac-key}") String hmacKey,
            @Value("${minipay.identity.real-name.provider}") String providerName) {
        if (hmacKey == null || hmacKey.length() < 32) {
            throw new IllegalStateException("Real-name HMAC key must contain at least 32 characters");
        }
        this.provider = provider;
        this.repository = repository;
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
        this.providerName = providerName;
    }

    public VerificationView verify(
            UUID userId, String idempotencyKey, String legalName,
            String idNumber, byte[] faceJpeg) {
        String name = legalName == null ? "" : legalName.strip();
        String number = idNumber == null ? "" : idNumber.strip().toUpperCase();
        validate(idempotencyKey, name, number, faceJpeg);
        byte[] requestHash = hmac(name + "\u0000" + number + "\u0000"
                + HexFormat.of().formatHex(digest(faceJpeg)));
        VerificationRow existing = repository.findByIdempotency(userId, idempotencyKey).orElse(null);
        if (existing != null) {
            return view(repository.requireSameRequest(existing, requestHash));
        }
        UUID verificationId = UuidV7.generate();
        repository.insert(
                verificationId, userId, idempotencyKey, requestHash,
                maskName(name), hmac(name), maskId(number), hmac(number), providerName);
        RealNameVerificationPort.VerificationResult result = provider.verify(name, number, faceJpeg);
        complete(verificationId, result);
        return view(repository.find(userId, verificationId).orElseThrow());
    }

    @Transactional
    void complete(UUID id, RealNameVerificationPort.VerificationResult result) {
        repository.complete(id, result.verified(), result.providerReference(), result.failureCode());
    }

    public VerificationView get(UUID userId, UUID verificationId) {
        return view(repository.find(userId, verificationId)
                .orElseThrow(() -> new RealNameVerificationRejectedException("VERIFICATION_NOT_FOUND")));
    }

    private void validate(String key, String name, String number, byte[] jpeg) {
        if (key == null || key.length() < 16 || key.length() > 128) {
            throw new RealNameVerificationRejectedException("INVALID_IDEMPOTENCY_KEY");
        }
        if (name.length() < 2 || name.length() > 64) {
            throw new RealNameVerificationRejectedException("LEGAL_NAME_INVALID");
        }
        if (!number.matches("^[1-9]\\d{16}[0-9X]$") || !validIdChecksum(number)) {
            throw new RealNameVerificationRejectedException("ID_NUMBER_INVALID");
        }
        if (jpeg == null || jpeg.length < 4 || jpeg.length > 1_048_576
                || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) {
            throw new RealNameVerificationRejectedException("FACE_IMAGE_INVALID");
        }
    }

    private boolean validIdChecksum(String value) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (value.charAt(i) - '0') * weights[i];
        return checks[sum % 11] == value.charAt(17);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private byte[] digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String maskName(String name) {
        return name.codePointCount(0, name.length()) <= 2
                ? name.substring(0, 1) + "*"
                : name.substring(0, 1) + "*".repeat(name.length() - 2) + name.substring(name.length() - 1);
    }

    private String maskId(String id) {
        return id.substring(0, 3) + "***********" + id.substring(id.length() - 4);
    }

    private VerificationView view(VerificationRow row) {
        return new VerificationView(
                row.verificationId(), row.status(), row.legalNameMasked(),
                row.idNumberMasked(), row.provider(), row.failureCode(), row.verifiedAt());
    }

    public record VerificationView(
            UUID verificationId, String status, String legalNameMasked,
            String idNumberMasked, String provider, String failureCode,
            java.time.Instant verifiedAt) {
    }
}
