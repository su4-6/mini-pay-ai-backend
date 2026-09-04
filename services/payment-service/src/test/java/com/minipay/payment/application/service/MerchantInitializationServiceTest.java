package com.minipay.payment.application.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.security.MerchantSecretConfigurationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MerchantInitializationServiceTest {
    @Test
    void provisionsOwnerWalletBeforeInitializingMerchantResources() {
        WalletInternalClient wallets = mock(WalletInternalClient.class);
        MerchantService merchants = mock(MerchantService.class);
        MerchantInitializationService service =
                new MerchantInitializationService(wallets, merchants);
        UUID ownerUserId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        UUID merchantId = UUID.fromString("019fb3d0-2000-7000-8000-000000000002");

        service.initialize(ownerUserId, merchantId);

        InOrder ordered = inOrder(wallets, merchants);
        ordered.verify(merchants).merchant(ownerUserId, merchantId);
        ordered.verify(wallets).openWallet(merchantId, ownerUserId);
        ordered.verify(merchants).initialize(ownerUserId, merchantId);
    }

    @Test
    void returnsStableProblemWhenMerchantSecretConfigurationIsInvalid() {
        WalletInternalClient wallets = mock(WalletInternalClient.class);
        MerchantService merchants = mock(MerchantService.class);
        MerchantInitializationService service =
                new MerchantInitializationService(wallets, merchants);
        UUID ownerUserId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        UUID merchantId = UUID.fromString("019fb3d0-2000-7000-8000-000000000002");
        when(merchants.initialize(ownerUserId, merchantId)).thenThrow(
                new MerchantSecretConfigurationException("MERCHANT_APP_SECRET_KEY is required"));

        assertThatThrownBy(() -> service.initialize(ownerUserId, merchantId))
                .isInstanceOfSatisfying(PaymentProblemException.class, problem -> {
                    org.assertj.core.api.Assertions.assertThat(problem.code())
                            .isEqualTo("MERCHANT_INITIALIZATION_UNAVAILABLE");
                    org.assertj.core.api.Assertions.assertThat(problem.status())
                            .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}
