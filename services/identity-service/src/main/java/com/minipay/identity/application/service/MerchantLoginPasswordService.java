package com.minipay.identity.application.service;

import com.minipay.identity.infrastructure.persistence.MerchantLoginCredentialRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-level merchant portal password with Argon2id hashing and lockout. */
@Service
public class MerchantLoginPasswordService {
    private final MerchantLoginCredentialRepository credentials;
    private final PasswordEncoder encoder;

    public MerchantLoginPasswordService(
            MerchantLoginCredentialRepository credentials, PasswordEncoder encoder) {
        this.credentials = credentials;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public boolean configured(UUID userId) {
        return credentials.exists(userId);
    }

    @Transactional
    public void verify(UUID userId, String password) {
        MerchantLoginCredentialRepository.Credential credential = credentials.lock(userId)
                .orElseThrow(() -> rejected("MERCHANT_PASSWORD_NOT_CONFIGURED"));
        if (!"ACTIVE".equals(credential.status())) {
            throw rejected("MERCHANT_PASSWORD_DISABLED");
        }
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(Instant.now())) {
            throw rejected("MERCHANT_PASSWORD_LOCKED");
        }
        if (!encoder.matches(password, credential.passwordHash())) {
            credentials.recordFailure(userId, credential.failedAttempts());
            throw rejected("MERCHANT_LOGIN_REJECTED");
        }
        credentials.clearFailures(userId);
    }

    @Transactional
    public PasswordStatus change(UUID userId, String currentPassword, String newPassword) {
        validate(newPassword);
        MerchantLoginCredentialRepository.Credential existing = credentials.lock(userId)
                .orElse(null);
        if (existing != null) {
            if (currentPassword == null || currentPassword.isBlank()
                    || !encoder.matches(currentPassword, existing.passwordHash())) {
                throw rejected("CURRENT_MERCHANT_PASSWORD_INVALID");
            }
        }
        credentials.save(userId, encoder.encode(newPassword));
        return new PasswordStatus(true, Instant.now());
    }

    @Transactional
    public PasswordStatus resetAfterSms(UUID userId, String newPassword) {
        validate(newPassword);
        credentials.save(userId, encoder.encode(newPassword));
        return new PasswordStatus(true, Instant.now());
    }

    private static void validate(String password) {
        if (password == null
                || !password.matches("^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{12,20}$")) {
            throw rejected("INVALID_MERCHANT_PASSWORD");
        }
    }

    private static LoginRejectedException rejected(String code) {
        return new LoginRejectedException(code);
    }

    public record PasswordStatus(boolean configured, Instant changedAt) {
    }
}
