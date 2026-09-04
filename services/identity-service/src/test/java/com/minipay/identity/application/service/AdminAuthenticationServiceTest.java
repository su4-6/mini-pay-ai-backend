package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminAuthenticationServiceTest {
    private final PhoneNumberService phones = mock(PhoneNumberService.class);
    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminAuthenticationService service =
            new AdminAuthenticationService(phones, accounts, encoder);

    @Test
    void settingAdministratorPasswordUpdatesTheLoginPasswordCredential() {
        UUID userId = UUID.randomUUID();
        when(encoder.encode("AdminSecure123")).thenReturn("encoded-admin-password");

        AdminAuthenticationService.PasswordStatus result =
                service.setLoginPassword(userId, "AdminSecure123");

        assertThat(result.configured()).isTrue();
        verify(accounts).saveLoginPassword(userId, "encoded-admin-password");
    }

    @Test
    void administratorPasswordUsesTheBackofficePasswordPolicy() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.setLoginPassword(userId, "short"))
                .isInstanceOf(LoginRejectedException.class);
    }
}
