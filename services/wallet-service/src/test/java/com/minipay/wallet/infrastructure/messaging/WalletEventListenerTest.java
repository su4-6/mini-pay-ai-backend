package com.minipay.wallet.infrastructure.messaging;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.wallet.application.service.WalletApplicationService;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class WalletEventListenerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "payment.transfer.processing",
            "payment.transfer.succeeded",
            "payment.transfer.failed"
    })
    void checkpointsTransferEventsWithoutPostingMoneyAgain(String eventType) throws Exception {
        WalletApplicationService wallets = Mockito.mock(WalletApplicationService.class);
        WalletInboxRepository inbox = Mockito.mock(WalletInboxRepository.class);
        UUID eventId = UUID.randomUUID();
        when(inbox.start(eventId, eventType, "wallet-service")).thenReturn(true);
        WalletEventListener listener = new WalletEventListener(
                wallets, inbox, new ObjectMapper());

        listener.onEvent("""
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "payload": {
                    "transferId": "%s",
                    "status": "PROCESSING"
                  }
                }
                """.formatted(eventId, eventType, UUID.randomUUID()));

        verify(inbox).complete(eventId, "wallet-service");
        verifyNoInteractions(wallets);
    }
}
