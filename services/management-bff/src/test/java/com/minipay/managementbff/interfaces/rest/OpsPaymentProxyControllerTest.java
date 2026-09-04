package com.minipay.managementbff.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.managementbff.infrastructure.security.RequestIdWebFilter;
import java.time.Instant;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpsPaymentProxyControllerTest {
    @Test
    void relaysServerSideTokenRequestIdAndConditionalHeaders() {
        ReactiveOAuth2AuthorizedClientManager manager = mock(
                ReactiveOAuth2AuthorizedClientManager.class);
        Authentication authentication = mock(Authentication.class);
        OAuth2AuthorizedClient authorizedClient = authorizedClient();
        when(manager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(Mono.just(authorizedClient));
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(request -> {
            forwarded.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"items\":[]}")
                    .build());
        });
        OpsPaymentProxyController controller = new OpsPaymentProxyController(
                manager, webClient, new ObjectMapper(), "http://payment:8080", "http://wallet:8080", "http://commerce:8085");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create(
                                "/api/v1/ops/merchants?page=0&size=20&merchantNo=M2026&name=%E6%98%9F%E6%B2%B3"))
                        .header("Idempotency-Key", "not-forwarded-on-get")
                        .build());
        exchange.getAttributes().put(RequestIdWebFilter.ATTRIBUTE, "req-ops-1");

        var response = controller.proxy(authentication, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forwarded.get().url().toString())
                .isEqualTo("http://payment:8080/api/v1/ops/merchants?page=0&size=20&merchantNo=M2026&name=%E6%98%9F%E6%B2%B3");
        assertThat(forwarded.get().headers().getFirst("Authorization"))
                .isEqualTo("Bearer server-access-token");
        assertThat(forwarded.get().headers().getFirst("X-Request-Id")).isEqualTo("req-ops-1");
    }

    @Test
    void mapsTimeoutToStableProblemDetails() {
        ReactiveOAuth2AuthorizedClientManager manager = mock(
                ReactiveOAuth2AuthorizedClientManager.class);
        Authentication authentication = mock(Authentication.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(Mono.just(authorizedClient()));
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new TimeoutException("timeout")));
        OpsPaymentProxyController controller = new OpsPaymentProxyController(
                manager, webClient, new ObjectMapper(), "http://payment:8080", "http://wallet:8080", "http://commerce:8085");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/ops/dashboard").build());
        exchange.getAttributes().put(RequestIdWebFilter.ATTRIBUTE, "req-ops-2");

        var response = controller.proxy(authentication, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("UPSTREAM_UNAVAILABLE", "req-ops-2")
                .doesNotContain("server-access-token");
    }

    @Test
    void routesFoodAndCollectionReadsToTheirDataOwners() {
        ReactiveOAuth2AuthorizedClientManager manager = mock(ReactiveOAuth2AuthorizedClientManager.class);
        Authentication authentication = mock(Authentication.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(Mono.just(authorizedClient()));
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(request -> {
            forwarded.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
        });
        OpsPaymentProxyController controller = new OpsPaymentProxyController(manager, webClient,
                new ObjectMapper(), "http://payment:8080", "http://wallet:8080", "http://commerce:8085");

        MockServerWebExchange food = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/ops/food-orders?page=0&size=20").build());
        food.getAttributes().put(RequestIdWebFilter.ATTRIBUTE, "req-food");
        controller.proxy(authentication, food).block();
        assertThat(forwarded.get().url().toString())
                .isEqualTo("http://commerce:8085/api/v1/ops/food-orders?page=0&size=20");

        MockServerWebExchange collection = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/ops/collection-records?page=1&size=20").build());
        collection.getAttributes().put(RequestIdWebFilter.ATTRIBUTE, "req-collection");
        controller.proxy(authentication, collection).block();
        assertThat(forwarded.get().url().toString())
                .isEqualTo("http://wallet:8080/api/v1/management/collection-records?page=1&size=20");
    }

    private static OAuth2AuthorizedClient authorizedClient() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("minipay-ops")
                .clientId("management-bff")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/minipay-ops")
                .authorizationUri("http://identity/oauth2/authorize")
                .tokenUri("http://identity/oauth2/token")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "server-access-token",
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-03T01:00:00Z"),
                Set.of("ops.dashboard.read", "ops.merchant.read", "ops.merchant.write"));
        return new OAuth2AuthorizedClient(registration, "admin-1", token);
    }
}
