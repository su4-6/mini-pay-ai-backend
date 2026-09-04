package com.minipay.wallet.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.wallet.application.service.WalletApplicationService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WalletEventListener {
    private final WalletApplicationService wallets;
    private final WalletInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final String consumerName;

    public WalletEventListener(
            WalletApplicationService wallets,
            WalletInboxRepository inbox,
            ObjectMapper objectMapper) {
        this.wallets = wallets;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.consumerName = "wallet-service";
    }

    @RabbitListener(queues = "#{walletEventQueue.name}")
    @Transactional
    public void onEvent(String json) throws IOException {
        JsonNode envelope = objectMapper.readTree(json);
        UUID eventId = UUID.fromString(requiredText(envelope, "eventId"));
        String eventType = requiredText(envelope, "eventType");
        if (!inbox.start(eventId, eventType, consumerName)) {
            return;
        }
        JsonNode payload = envelope.path("payload");
        switch (eventType) {
            case "identity.user.opened" -> wallets.openWallet(
                    eventId,
                    UUID.fromString(requiredText(payload, "userId")));
            case "payment.recharge.succeeded" -> wallets.postRecharge(
                    eventId,
                    UUID.fromString(requiredText(payload, "rechargeId")),
                    requiredText(payload, "rechargeNo"),
                    UUID.fromString(requiredText(payload, "userId")),
                    payload.path("amountCent").longValue());
            // Transfer bills are written by the Wallet TCC branches. Consuming the
            // lifecycle event here records the wallet projection checkpoint without
            // applying the money movement a second time.
            case "payment.transfer.processing",
                 "payment.transfer.succeeded",
                 "payment.transfer.failed" -> {
                requiredText(payload, "transferId");
                requiredText(payload, "status");
            }
            default -> throw new IllegalArgumentException("Unsupported wallet event type");
        }
        inbox.complete(eventId, consumerName);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing event field " + field);
        }
        return value;
    }
}
