package com.minipay.payment.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minipay.payment.application.service.TransferService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AgentPaymentControllerTest {

    @Test
    void preparesAgentTransferWithCanonicalAiSource() {
        TransferService transfers = mock(TransferService.class);
        AgentPaymentController controller = new AgentPaymentController(transfers);
        UUID payerUserId = UUID.randomUUID();
        UUID receiverUserId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("user_id", payerUserId.toString())
                .build();

        controller.prepareTransfer(
                jwt,
                "agent-run:transfer",
                new AgentPaymentController.PrepareTransferRequest(receiverUserId, 100L, null));

        verify(transfers).create(
                payerUserId,
                "agent-run:transfer",
                receiverUserId,
                100L,
                null,
                "AI");
    }
}
