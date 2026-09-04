package com.minipay.adminbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

class AdminProxyControllerTest {
    @Test
    void routesFoodAndCollectionReadsToTheirDataOwners() {
        var clients = mock(ReactiveOAuth2AuthorizedClientManager.class);
        var authentication = mock(Authentication.class);
        when(clients.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(Mono.just(client()));
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            forwarded.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
        });
        var controller = new AdminProxyController(clients, builder, "http://identity", "http://payment",
                "http://wallet", "http://commerce");

        controller.proxy(authentication, MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/admin/orders/food-orders?page=0").build())).block();
        assertThat(forwarded.get().url().toString())
                .isEqualTo("http://commerce/api/v1/admin/orders/food-orders?page=0");

        controller.proxy(authentication, MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/admin/orders/collection-records?page=0").build())).block();
        assertThat(forwarded.get().url().toString())
                .isEqualTo("http://wallet/api/v1/admin/orders/collection-records?page=0");
    }

    private static OAuth2AuthorizedClient client() {
        var registration = ClientRegistration.withRegistrationId("minipay-admin")
                .clientId("admin-bff").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/minipay-admin")
                .authorizationUri("http://identity/oauth2/authorize")
                .tokenUri("http://identity/oauth2/token").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
                Instant.parse("2026-08-10T00:00:00Z"), Instant.parse("2026-08-10T01:00:00Z"),
                Set.of("admin.order.read", "admin.wallet.read"));
        return new OAuth2AuthorizedClient(registration, "admin", token);
    }
}
