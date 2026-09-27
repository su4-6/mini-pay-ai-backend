package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.minipay.consumerbff.application.port.IdentityConsumerGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Authenticated consumer calls against Identity. The payment password is placed directly into the
 * request body and never copied into a field, session attribute, log line or exception message.
 */
public class HttpIdentityConsumerGateway implements IdentityConsumerGateway {

    private static final JsonNode MISSING = JsonNodeFactory.instance.missingNode();

    private final WebClient identity;

    public HttpIdentityConsumerGateway(WebClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<IssuedPaymentAuthorization> issuePaymentAuthorization(
            String accessToken,
            String idempotencyKey,
            String subjectType,
            String subjectId,
            long amountCent,
            String deviceId,
            String paymentPassword,
            String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectType", subjectType);
        body.put("subjectId", subjectId);
        body.put("amountCent", amountCent);
        body.put("deviceId", deviceId);
        body.put("payPassword", paymentPassword);
        return identity.post()
                .uri("/api/v1/payment-authorizations")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Request-Id", requestId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(MISSING)
                        .flatMap(document -> response.statusCode().is2xxSuccessful()
                                ? Mono.just(toAuthorization(document))
                                : Mono.error(UpstreamProblems.from(
                                        response.statusCode().value(), document,
                                        "PAYMENT_AUTHORIZATION_REJECTED"))));
    }

    private IssuedPaymentAuthorization toAuthorization(JsonNode document) {
        String token = Values.text(document, "paymentAuthToken");
        if (token == null) {
            throw UpstreamProblems.gateway(
                    "PAYMENT_AUTHORIZATION_INVALID", "上游未返回一次性授权令牌");
        }
        return new IssuedPaymentAuthorization(
                Values.text(document, "authorizationId"), token);
    }

    @Override
    public Mono<Void> setInitialPaymentPassword(
            org.springframework.web.server.WebSession session,
            String accessToken,
            String paymentPassword,
            String requestId) {
        return identity.put()
                .uri("/api/v1/users/me/payment-password")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("paymentPassword", paymentPassword))
                .exchangeToMono(response -> response.releaseBody()
                        .then(response.statusCode().is2xxSuccessful()
                                ? Mono.empty()
                                : Mono.error(UpstreamProblems.from(
                                        response.statusCode().value(), MISSING,
                                        "PAYMENT_PASSWORD_REJECTED"))));
    }
}
