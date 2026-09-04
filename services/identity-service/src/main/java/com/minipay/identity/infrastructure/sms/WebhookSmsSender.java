package com.minipay.identity.infrastructure.sms;

import com.minipay.identity.application.port.SmsSender;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "minipay.identity.sms.provider", havingValue = "webhook")
public class WebhookSmsSender implements SmsSender {
    private final RestClient restClient;
    private final String bearerToken;

    public WebhookSmsSender(
            RestClient.Builder restClientBuilder,
            @Value("${minipay.identity.sms.webhook-url}") String webhookUrl,
            @Value("${minipay.identity.sms.webhook-token}") String bearerToken) {
        this.restClient = restClientBuilder.baseUrl(webhookUrl).build();
        this.bearerToken = bearerToken;
    }

    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        send(normalizedPhone, code, "OPS_LOGIN");
    }

    @Override
    public void sendConsumerLoginCode(String normalizedPhone, String code) {
        send(normalizedPhone, code, "CONSUMER_LOGIN");
    }

    private void send(String normalizedPhone, String code, String purpose) {
        restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(Map.of(
                        "phone", normalizedPhone,
                        "code", code,
                        "purpose", purpose))
                .retrieve()
                .toBodilessEntity();
    }
}
