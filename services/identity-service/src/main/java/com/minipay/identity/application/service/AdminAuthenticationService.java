package com.minipay.identity.application.service;

import com.minipay.identity.domain.model.AdminPrincipal;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository.AdminAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthenticationService {
    private static final String DUMMY_PASSWORD_HASH =
            "$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as";

    private final PhoneNumberService phoneNumbers;
    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthenticationService(
            PhoneNumberService phoneNumbers,
            AdminAccountRepository accounts,
            PasswordEncoder passwordEncoder) {
        this.phoneNumbers = phoneNumbers;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminPrincipal authenticatePassword(
            String rawPhone,
            String password,
            RequestMetadata metadata) {
        String phone = normalizeOrReject(rawPhone);
        byte[] phoneHash = phoneNumbers.hash(phone);
        Optional<AdminAccount> candidate = accounts.findByPhoneHash(phoneHash);
        AdminAccount account = candidate.orElse(null);
        String hash = account == null ? DUMMY_PASSWORD_HASH : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password == null ? "" : password, hash);

        if (account == null || !passwordMatches || !eligible(account)) {
            if (account != null && !passwordMatches) {
                accounts.recordPasswordFailure(account.userId());
            }
            throw new LoginRejectedException(
                    resultFor(account, passwordMatches),
                    account == null ? null : account.userId());
        }

        accounts.resetFailures(account.userId());
        return account.principal();
    }

    public Optional<AdminAccount> eligibleSmsAccount(String rawPhone) {
        try {
            String phone = phoneNumbers.normalize(rawPhone);
            return accounts.findByPhoneHash(phoneNumbers.hash(phone)).filter(this::eligible);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public AdminPrincipal authenticateSms(String rawPhone, RequestMetadata metadata) {
        String phone = normalizeOrReject(rawPhone);
        byte[] phoneHash = phoneNumbers.hash(phone);
        AdminAccount account = accounts.findByPhoneHash(phoneHash)
                .filter(this::eligible)
                .orElseThrow(() -> new LoginRejectedException("REJECTED"));
        accounts.resetFailures(account.userId());
        return account.principal();
    }

    public AdminPrincipal loadPrincipal(UUID userId) {
        return accounts.findPrincipal(userId)
                .orElseThrow(() -> new LoginRejectedException("LOGIN_REJECTED"));
    }

    public PasswordStatus setLoginPassword(UUID userId, String newPassword) {
        if (newPassword == null
                || !newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{12,20}$")) {
            throw new LoginRejectedException("INVALID_ADMIN_PASSWORD");
        }
        accounts.saveLoginPassword(userId, passwordEncoder.encode(newPassword));
        return new PasswordStatus(true, Instant.now());
    }

    private boolean eligible(AdminAccount account) {
        return account.active() && account.backoffice() && !account.locked(Instant.now());
    }

    private String normalizeOrReject(String rawPhone) {
        try {
            return phoneNumbers.normalize(rawPhone);
        } catch (IllegalArgumentException exception) {
            throw new LoginRejectedException("LOGIN_REJECTED");
        }
    }

    private String resultFor(AdminAccount account, boolean passwordMatches) {
        if (account == null || !passwordMatches) {
            return "REJECTED";
        }
        if (account.locked(Instant.now())) {
            return "LOCKED";
        }
        if (!account.active()) {
            return "DISABLED";
        }
        return account.backoffice() ? "REJECTED" : "ROLE_DENIED";
    }

    public record RequestMetadata(String clientAddress, String userAgent, String requestId) {
    }

    public record PasswordStatus(boolean configured, Instant changedAt) {
    }
}
