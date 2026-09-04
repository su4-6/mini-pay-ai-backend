package com.minipay.wallet.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minipay.wallet.application.service.WalletApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class WalletControllerTest {
    @Test
    void merchantOwnerCanReadPersonalWalletBeforeConsumerOnboarding() {
        WalletApplicationService wallets = mock(WalletApplicationService.class);
        WalletController controller = new WalletController(wallets);
        UUID userId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        Jwt token = new Jwt(
                "merchant-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", userId.toString(),
                        "user_id", userId.toString(),
                        "aud", List.of("merchant-api"),
                        "roles", List.of("consumer"),
                        "onboarding_completed", false,
                        "real_name_verified", false));

        controller.getWallet(new JwtAuthenticationToken(token));

        verify(wallets).getWallet(userId);
    }

    @Test
    void recentTransferCounterpartiesAreScopedToAuthenticatedUser() {
        WalletApplicationService wallets = mock(WalletApplicationService.class);
        WalletController controller = new WalletController(wallets);
        UUID userId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");

        controller.recentTransferCounterparties(authentication(userId), 1, 50);

        verify(wallets).recentTransferCounterparties(userId, 1, 50);
    }

    @Test
    void transferMonthUsesShanghaiCalendarBoundaries() {
        WalletApplicationService wallets = mock(WalletApplicationService.class);
        WalletController controller = new WalletController(wallets);
        UUID userId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        UUID counterparty = UUID.fromString("019fb3d0-2000-7000-8000-000000000002");

        controller.transferRecords(authentication(userId), counterparty, "INCOME",
                "2026-08", "SUCCEEDED", 1, 20);

        verify(wallets).transferRecords(userId, counterparty, "INCOME", "SUCCEEDED",
                Instant.parse("2026-07-31T16:00:00Z"), Instant.parse("2026-08-31T16:00:00Z"), 1, 20);
    }

    @Test
    void collectionRecordsAreScopedToAuthenticatedUser() {
        WalletApplicationService wallets = mock(WalletApplicationService.class);
        WalletController controller = new WalletController(wallets);
        UUID userId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");

        controller.collectionRecords(authentication(userId), "MERCHANT", "MONTH", 2, 20);

        verify(wallets).collectionRecords(userId, "MERCHANT", "MONTH", 2, 20);
    }

    private static JwtAuthenticationToken authentication(UUID userId) {
        Jwt token = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of(
                        "sub", userId.toString(), "user_id", userId.toString(),
                        "aud", List.of("merchant-api"), "roles", List.of("consumer")));
        return new JwtAuthenticationToken(token);
    }
}
