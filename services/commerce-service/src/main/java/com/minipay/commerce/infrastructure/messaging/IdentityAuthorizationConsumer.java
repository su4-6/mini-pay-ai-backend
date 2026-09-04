package com.minipay.commerce.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.YshopFoodIntegrationService;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class IdentityAuthorizationConsumer {
    private final ObjectMapper json;
    private final YshopFoodIntegrationService food;

    public IdentityAuthorizationConsumer(ObjectMapper json, YshopFoodIntegrationService food) {
        this.json = json;
        this.food = food;
    }

    @RabbitListener(queues = CommerceMessagingConfiguration.IDENTITY_AUTHORIZATION_QUEUE)
    public void consume(String body) throws Exception {
        JsonNode envelope = json.readTree(body);
        String eventType = envelope.path("eventType").asText();
        JsonNode payload = envelope.path("payload");
        UUID userId = UUID.fromString(payload.path("userId").asText());
        String applicationId = payload.path("applicationId").asText("yshop-food");
        if (!"yshop-food".equals(applicationId)) return;
        switch (eventType) {
            case "identity.application-authorization.granted" -> food.bind(userId);
            case "identity.application-authorization.revoked" -> food.unbind(userId);
            case "identity.user-disclosure-profile.changed" -> food.syncProfileIfBound(userId);
            default -> throw new IllegalArgumentException("Unsupported identity authorization event");
        }
    }
}
