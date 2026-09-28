package com.minipay.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.application.service.TransferService;
import com.minipay.payment.domain.model.TransferOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TransferControllerTest {

    @Test
    void listsOnlyTheAuthenticatedConsumersRecentTransfers() {
        TransferService transfers = mock(TransferService.class);
        TransferController controller = new TransferController(transfers);
        UUID userId = UUID.randomUUID();
        TransferOrder order = new TransferOrder(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                100L, "SUCCEEDED", null, Instant.parse("2026-09-28T10:00:00Z"));
        when(transfers.listOrders(userId, 20)).thenReturn(List.of(order));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", userId.toString())
                .claim("onboarding_completed", true)
                .claim("real_name_verified", true)
                .claim("real_name_status", "VERIFIED")
                .build();

        List<TransferOrder> result = controller.list(jwt, 20);

        assertThat(result).containsExactly(order);
        verify(transfers).listOrders(userId, 20);
    }
}
