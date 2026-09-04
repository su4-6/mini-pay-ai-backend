package com.minipay.identity.application.service;

import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository;
import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository.AuthorizationRow;
import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository.CredentialRow;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentAuthorizationService {
    private static final Set<String> SUBJECT_TYPES =
            Set.of("TRANSFER_INTENT", "PAYMENT_ORDER", "WITHDRAWAL_ORDER",
                    "RECHARGE_ORDER", "BANK_CARD_BALANCE_QUERY");

    private final PaymentAuthorizationRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final byte[] tokenKey;

    public PaymentAuthorizationService(
            PaymentAuthorizationRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${minipay.identity.payment-authorization-token-key}") String tokenKey) {
        if (tokenKey == null || tokenKey.length() < 32) {
            throw new IllegalStateException(
                    "Payment authorization token key must contain at least 32 characters");
        }
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenKey = tokenKey.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public IssuedAuthorization issue(
            UUID userId,
            String idempotencyKey,
            String subjectType,
            UUID subjectId,
            long amountCent,
            String deviceId,
            String payPassword) {
        validateRequest(idempotencyKey, subjectType, amountCent, deviceId, payPassword);
        if (!repository.isRealNameVerified(userId)) {
            throw rejected("REAL_NAME_VERIFICATION_REQUIRED");
        }
        byte[] requestHash = sha256(
                subjectType + ":" + subjectId + ":" + amountCent + ":" + deviceId);
        AuthorizationRow existing = repository.findByIdempotency(
                userId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw rejected("IDEMPOTENCY_KEY_REUSED");
            }
            if (existing.consumedAt() != null || existing.expiresAt().isBefore(Instant.now())) {
                throw rejected("PAYMENT_AUTHORIZATION_EXPIRED");
            }
            return issued(existing);
        }
        CredentialRow credential = repository.lockPaymentCredential(userId)
                .orElseThrow(() -> rejected("PAYMENT_PASSWORD_NOT_SET"));
        if (!"ACTIVE".equals(credential.status())) {
            throw rejected("PAYMENT_PASSWORD_DISABLED");
        }
        if (credential.lockedUntil() != null
                && credential.lockedUntil().isAfter(Instant.now())) {
            throw rejected("PAYMENT_PASSWORD_LOCKED");
        }
        if (!passwordEncoder.matches(payPassword, credential.passwordHash())) {
            repository.recordPasswordFailure(userId, credential.failedAttempts());
            throw rejected("PAYMENT_PASSWORD_INVALID");
        }
        repository.clearPasswordFailures(userId);
        UUID authorizationId = UuidV7.generate();
        Instant expiresAt = Instant.now().plus(2, ChronoUnit.MINUTES);
        AuthorizationRow row = new AuthorizationRow(
                authorizationId,
                userId,
                idempotencyKey,
                requestHash,
                subjectId,
                subjectType,
                subjectId,
                amountCent,
                deviceId,
                new byte[0],
                expiresAt,
                null);
        String token = token(row);
        repository.insertAuthorization(
                authorizationId,
                userId,
                idempotencyKey,
                requestHash,
                subjectId,
                subjectType,
                subjectId,
                amountCent,
                deviceId,
                sha256(token),
                expiresAt);
        return new IssuedAuthorization(authorizationId, token, expiresAt);
    }

    @Transactional
    public ConsumedAuthorization verifyAndConsume(
            String token,
            UUID userId,
            String subjectType,
            UUID subjectId,
            long amountCent,
            String deviceId) {
        if (token == null || token.isBlank()) {
            throw rejected("PAYMENT_AUTHORIZATION_INVALID");
        }
        AuthorizationRow row = repository.lockByTokenHash(sha256(token))
                .orElseThrow(() -> rejected("PAYMENT_AUTHORIZATION_INVALID"));
        if (!row.userId().equals(userId)
                || !row.subjectType().equals(subjectType)
                || !row.subjectId().equals(subjectId)
                || row.amountCent() != amountCent
                || deviceId == null || !row.deviceId().equals(deviceId)
                || row.consumedAt() != null
                || row.expiresAt().isBefore(Instant.now())
                || !MessageDigest.isEqual(
                        token(row).getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            throw rejected("PAYMENT_AUTHORIZATION_INVALID");
        }
        repository.consume(row.authorizationId());
        return new ConsumedAuthorization(row.authorizationId(), true);
    }

    private IssuedAuthorization issued(AuthorizationRow row) {
        return new IssuedAuthorization(row.authorizationId(), token(row), row.expiresAt());
    }

    private String token(AuthorizationRow row) {
        String binding = row.authorizationId() + ":" + row.userId() + ":"
                + row.subjectType() + ":" + row.subjectId() + ":" + row.amountCent()
                + ":" + row.deviceId() + ":" + row.expiresAt().getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((row.authorizationId() + ".").getBytes(StandardCharsets.UTF_8))
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(binding));
    }

    private void validateRequest(
            String idempotencyKey,
            String subjectType,
            long amountCent,
            String deviceId,
            String payPassword) {
        if (idempotencyKey == null
                || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128) {
            throw rejected("INVALID_IDEMPOTENCY_KEY");
        }
        if (!SUBJECT_TYPES.contains(subjectType)) {
            throw rejected("INVALID_AUTHORIZATION_SUBJECT");
        }
        boolean balanceQuery = "BANK_CARD_BALANCE_QUERY".equals(subjectType);
        if ((balanceQuery && amountCent != 0L)
                || (!balanceQuery && (amountCent < 1 || amountCent > 1_000_000L))) {
            throw rejected("AMOUNT_OUT_OF_RANGE");
        }
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128) {
            throw rejected("DEVICE_ID_INVALID");
        }
        if (payPassword == null || !payPassword.matches("\\d{6}")) {
            throw rejected("PAYMENT_PASSWORD_INVALID");
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private PaymentAuthorizationRejectedException rejected(String code) {
        return new PaymentAuthorizationRejectedException(code);
    }

    public record IssuedAuthorization(
            UUID authorizationId,
            String paymentAuthToken,
            Instant expiresAt) {
    }

    public record ConsumedAuthorization(UUID authorizationId, boolean valid) {
    }
}
