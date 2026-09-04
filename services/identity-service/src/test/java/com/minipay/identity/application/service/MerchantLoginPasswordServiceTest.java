package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.infrastructure.persistence.MerchantLoginCredentialRepository;
import com.minipay.identity.infrastructure.persistence.MerchantLoginCredentialRepository.Credential;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class MerchantLoginPasswordServiceTest {
    private static final UUID USER_ID =
            UUID.fromString("019fb3d0-2000-7000-8000-000000000002");

    @Mock MerchantLoginCredentialRepository credentials;
    @Mock PasswordEncoder encoder;
    @InjectMocks MerchantLoginPasswordService service;

    @Test
    void reportsWhetherMerchantLoginPasswordExists() {
        when(credentials.exists(USER_ID)).thenReturn(true);

        assertThat(service.configured(USER_ID)).isTrue();
    }

    @Test
    void firstPasswordCanBeSetWithoutCurrentPassword() {
        when(credentials.lock(USER_ID)).thenReturn(Optional.empty());
        when(encoder.encode("MerchantPass123")).thenReturn("encoded-password");

        MerchantLoginPasswordService.PasswordStatus result =
                service.change(USER_ID, "", "MerchantPass123");

        assertThat(result.configured()).isTrue();
        verify(credentials).save(USER_ID, "encoded-password");
    }

    @Test
    void existingPasswordStillRequiresCurrentPassword() {
        when(credentials.lock(USER_ID)).thenReturn(Optional.of(
                new Credential("old-hash", "ACTIVE", 0, null, Instant.now())));

        assertThatThrownBy(() -> service.change(USER_ID, "", "MerchantPass123"))
                .isInstanceOfSatisfying(LoginRejectedException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("CURRENT_MERCHANT_PASSWORD_INVALID"));
        verify(credentials, never()).save(USER_ID, "encoded-password");
    }
}
