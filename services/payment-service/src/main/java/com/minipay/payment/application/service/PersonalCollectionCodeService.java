package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.PersonalCollectionCode;
import com.minipay.payment.domain.model.ScanResolution;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.CollectionCodeRow;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalCollectionCodeService {
    private static final Logger log = LoggerFactory.getLogger(PersonalCollectionCodeService.class);
    private static final String NOTICE =
            "个人收款码仅用于 MiniPay 演示沙箱；扫码后仍需确认金额并完成支付授权。";

    private final PaymentRepository repository;
    private final IdentityInternalClient identity;
    private final byte[] signingKey;
    private final Clock clock;

    public PersonalCollectionCodeService(
            PaymentRepository repository,
            IdentityInternalClient identity,
            @Value("${minipay.channels.collection-code.signing-key}") String signingKey,
            Clock clock) {
        if (signingKey == null || signingKey.length() < 32) {
            throw new IllegalStateException(
                    "Collection-code signing key must contain at least 32 characters");
        }
        this.repository = repository;
        this.identity = identity;
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Transactional
    public PersonalCollectionCode current(UUID ownerId) {
        Instant now = clock.instant();
        repository.expireCollectionCodes(ownerId, now);
        CollectionCodeRow row = repository.findCurrentCollectionCode(ownerId, now).orElse(null);
        if (row != null && row.expiresAt().isBefore(now.plus(90, ChronoUnit.SECONDS))) {
            repository.revokeCollectionCode(row.codeId());
            row = null;
        }
        if (row == null) {
            UUID codeId = UuidV7.generate();
            Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String token = token(codeId, ownerId, expiresAt, nonce);
            repository.insertCollectionCode(
                    codeId,
                    ownerId,
                    nonce,
                    RequestSupport.hash(token),
                    expiresAt);
            row = new CollectionCodeRow(
                    codeId,
                    ownerId,
                    nonce,
                    RequestSupport.hash(token),
                    "ACTIVE",
                    expiresAt);
        }
        return new PersonalCollectionCode(
                "PERSONAL_COLLECTION",
                deepLink(token(row.codeId(), row.ownerId(), row.expiresAt(), row.nonce())),
                row.expiresAt(),
                NOTICE);
    }

    public ScanResolution resolve(UUID scannerUserId, String deepLink) {
        String token = extractToken(deepLink);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw invalidCode("invalid_token_shape", token);
        }
        byte[] suppliedSignature;
        try {
            suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw invalidCode("invalid_signature_encoding", token);
        }
        if (!MessageDigest.isEqual(hmac(parts[0]), suppliedSignature)) {
            throw invalidCode("signature_mismatch", token);
        }
        String payload;
        try {
            payload = new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidCode("invalid_payload_encoding", token);
        }
        String[] fields = payload.split(":", -1);
        if (fields.length != 5 || !"v1".equals(fields[0])) {
            throw invalidCode("invalid_payload_shape", token);
        }
        UUID codeId;
        UUID ownerId;
        Instant expiresAt;
        try {
            codeId = UUID.fromString(fields[1]);
            ownerId = UUID.fromString(fields[2]);
            expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[3]));
        } catch (RuntimeException exception) {
            throw invalidCode("invalid_payload_fields", token);
        }
        CollectionCodeRow stored = repository.findCollectionCodeByHash(
                        RequestSupport.hash(token))
                .orElseThrow(() -> invalidCode("stored_code_missing", token));
        Instant now = clock.instant();
        if (!stored.codeId().equals(codeId)
                || !stored.ownerId().equals(ownerId)
                || !stored.nonce().equals(fields[4])
                || stored.expiresAt().getEpochSecond() != expiresAt.getEpochSecond()) {
            throw invalidCode("stored_code_mismatch", token);
        }
        if (!"ACTIVE".equals(stored.status())) {
            throw invalidCode("stored_code_inactive", token);
        }
        if (expiresAt.isBefore(now)) {
            throw invalidCode("code_expired", token);
        }
        if (ownerId.equals(scannerUserId)) {
            log.info(
                    "personal_collection_code_rejected reason=self_collection_code token_fingerprint={}",
                    tokenFingerprint(token));
            throw new PaymentProblemException(
                    "SELF_COLLECTION_CODE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        IdentityInternalClient.ConsumerPaymentProfile profile =
                identity.consumerPaymentProfile(ownerId);
        if (!ownerId.equals(profile.userId())) {
            throw invalidCode("recipient_profile_mismatch", token);
        }
        String legalNameMasked = profile.legalNameMasked();
        String receiverDisplay = legalNameMasked == null || legalNameMasked.isBlank()
                ? profile.nickname()
                : profile.nickname() + "（" + legalNameMasked + "）";
        return new ScanResolution(
                "PERSONAL_COLLECTION",
                ownerId,
                receiverDisplay,
                profile.nickname(),
                profile.avatarUrl(),
                legalNameMasked);
    }

    private String token(UUID codeId, UUID ownerId, Instant expiresAt, String nonce) {
        String payload = "v1:" + codeId + ":" + ownerId + ":"
                + expiresAt.getEpochSecond() + ":" + nonce;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(encoded));
    }

    private String deepLink(String token) {
        return "minipay://collect/personal?token=" + token;
    }

    private String extractToken(String deepLink) {
        try {
            URI uri = URI.create(deepLink);
            if (!"minipay".equalsIgnoreCase(uri.getScheme())
                    || !"collect".equalsIgnoreCase(uri.getHost())
                    || !"/personal".equals(uri.getPath())) {
                throw invalidCode("malformed_uri", null);
            }
            String query = uri.getRawQuery();
            if (query == null) throw invalidCode("missing_query", null);
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "token".equals(parts[0])) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            throw invalidCode("missing_token", null);
        } catch (IllegalArgumentException exception) {
            throw invalidCode("malformed_uri", null);
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private PaymentProblemException invalidCode(String reason, String token) {
        log.info(
                "personal_collection_code_rejected reason={} token_fingerprint={}",
                reason,
                tokenFingerprint(token));
        return new PaymentProblemException(
                "INVALID_OR_EXPIRED_COLLECTION_CODE", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private String tokenFingerprint(String token) {
        return token == null ? "unavailable" : HexFormat.of()
                .formatHex(RequestSupport.hash(token), 0, 6);
    }
}
