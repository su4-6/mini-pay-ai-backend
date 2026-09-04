package com.minipay.managementbff.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class LoginAuditProxyControllerTest {

    @Test
    void usesAuthorizedClientManagerSoExpiredAccessTokenCanBeRefreshed() {
        ReactiveOAuth2AuthorizedClientManager manager =
                mock(ReactiveOAuth2AuthorizedClientManager.class);
        AtomicReference<HttpHeaders> upstreamHeaders = new AtomicReference<>();
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(request -> {
            upstreamHeaders.set(request.headers());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"items\":[]}")
                    .build());
        });
        OAuth2AuthorizedClient refreshedClient = refreshedClient();
        AtomicReference<OAuth2AuthorizeRequest> authorizeRequest = new AtomicReference<>();
        when(manager.authorize(any())).thenAnswer(invocation -> {
            authorizeRequest.set(invocation.getArgument(0));
            return Mono.just(refreshedClient);
        });

        LoginAuditProxyController controller = new LoginAuditProxyController(
                manager, webClient, new ObjectMapper(), "http://identity");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "admin-user", "not-used", java.util.List.of());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .get("/api/v1/login-audits?page=0&size=20"));

        var response = controller.list(0, 20, authentication, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"items\":[]}");
        assertThat(upstreamHeaders.get().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer refreshed-access-token");
        assertThat(authorizeRequest.get().getClientRegistrationId()).isEqualTo("minipay-ops");
        Object serverWebExchange = authorizeRequest.get()
                .getAttribute(ServerWebExchange.class.getName());
        assertThat(serverWebExchange).isSameAs(exchange);
    }

    private OAuth2AuthorizedClient refreshedClient() {
        Instant now = Instant.now();
        ClientRegistration registration = ClientRegistration
                .withRegistrationId("minipay-ops")
                .clientId("minipay-management-bff")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/minipay-ops")
                .authorizationUri("http://identity/oauth2/authorize")
                .tokenUri("http://identity/oauth2/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "refreshed-access-token",
                now,
                now.plusSeconds(600));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "rotated-refresh-token",
                now,
                now.plusSeconds(3600));
        return new OAuth2AuthorizedClient(
                registration, "admin-user", accessToken, refreshToken);
    }
}
