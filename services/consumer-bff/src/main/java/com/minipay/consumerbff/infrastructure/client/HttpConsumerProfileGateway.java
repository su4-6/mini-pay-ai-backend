package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.minipay.consumerbff.application.port.ConsumerProfileGateway;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway.OAuthTokenSet;
import com.minipay.consumerbff.domain.identity.ConsumerIdentity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reads the consumer profile document. Login uses it only for the nickname, so a failing or
 * unreachable profile read degrades to the masked mobile and never fails the sign-in.
 */
public class HttpConsumerProfileGateway implements ConsumerProfileGateway {

    private static final JsonNode MISSING = JsonNodeFactory.instance.missingNode();

    private final WebClient identity;

    public HttpConsumerProfileGateway(WebClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<ConsumerIdentity> load(
            OAuthTokenSet tokens,
            String userId,
            String fallbackMaskedPhone,
            boolean payPasswordSet,
            boolean onboardingRequired,
            String realNameStatus,
            boolean realNameVerified,
            String requestId) {
        return loadDisplayName(tokens, requestId)
                .defaultIfEmpty("")
                .map(displayName -> new ConsumerIdentity(
                        userId,
                        fallbackMaskedPhone,
                        displayName,
                        payPasswordSet,
                        onboardingRequired,
                        realNameStatus,
                        realNameVerified));
    }

    @Override
    public Mono<String> loadDisplayName(OAuthTokenSet tokens, String requestId) {
        return identity.get()
                .uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("X-Request-Id", requestId)
                .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(MISSING)
                        .map(document -> response.statusCode().is2xxSuccessful()
                                ? Values.text(document, "nickname")
                                : null))
                .onErrorResume(ignored -> Mono.empty())
                .filter(name -> name != null && !name.isBlank());
    }
}
