package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.infrastructure.persistence.MerchantOwnerAccountRepository;
import com.minipay.identity.infrastructure.persistence.MerchantOwnerAccountRepository.MerchantOwnerAccount;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MerchantOwnerAccountServiceTest {
    private static final UUID USER_ID =
            UUID.fromString("019fb3d0-2000-7000-8000-000000000001");

    @Mock MerchantOwnerAccountRepository accounts;
    @Mock PhoneNumberService phones;
    @InjectMocks MerchantOwnerAccountService service;

    @Test
    void reusesExistingActiveAccountAndAddsRole() {
        when(phones.normalize("13800000001")).thenReturn("13800000001");
        when(phones.hash("13800000001")).thenReturn(new byte[]{1, 2, 3});
        when(accounts.findByPhoneHash(any()))
                .thenReturn(Optional.of(new MerchantOwnerAccount(
                        USER_ID, "merchant_abc", "ACTIVE", "SMS_ONLY")));

        MerchantOwnerAccountService.ResolvedMerchantOwner resolved =
                service.resolve("13800000001", "张三", "req-1");

        assertThat(resolved.userId()).isEqualTo(USER_ID);
        assertThat(resolved.newlyCreated()).isFalse();
        verify(accounts).addMerchantOwnerRole(USER_ID);
        verify(accounts, never()).createSmsOnly(any(), any(), any());
    }

    @Test
    void createsSmsOnlyAccountWhenPhoneNotRegistered() {
        when(phones.normalize("13800000001")).thenReturn("13800000001");
        when(phones.hash("13800000001")).thenReturn(new byte[]{1, 2, 3});
        when(accounts.findByPhoneHash(any())).thenReturn(Optional.empty());
        when(accounts.createSmsOnly(any(), eq("张三"), eq("req-2")))
                .thenReturn(new MerchantOwnerAccount(
                        USER_ID, "merchant_abc", "ACTIVE", "SMS_ONLY"));

        MerchantOwnerAccountService.ResolvedMerchantOwner resolved =
                service.resolve("13800000001", "张三", "req-2");

        assertThat(resolved.userId()).isEqualTo(USER_ID);
        assertThat(resolved.newlyCreated()).isTrue();
        verify(accounts).addMerchantOwnerRole(USER_ID);
    }

    @Test
    void rejectsDisabledAccount() {
        when(phones.normalize("13800000001")).thenReturn("13800000001");
        when(phones.hash("13800000001")).thenReturn(new byte[]{1, 2, 3});
        when(accounts.findByPhoneHash(any()))
                .thenReturn(Optional.of(new MerchantOwnerAccount(
                        USER_ID, "merchant_abc", "DISABLED", "SMS_ONLY")));

        assertThatThrownBy(() -> service.resolve("13800000001", "张三", "req-3"))
                .isInstanceOfSatisfying(MerchantOwnerRejectedException.class,
                        error -> assertThat(error.code()).isEqualTo("ACCOUNT_DISABLED"));
        verify(accounts, never()).addMerchantOwnerRole(any());
        verify(accounts, never()).createSmsOnly(any(), any(), any());
    }
}
