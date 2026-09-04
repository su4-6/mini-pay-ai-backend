package com.minipay.managementbff.infrastructure.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class OAuthAuthorizationRevocationLogoutHandler implements ServerLogoutHandler {
    private static final Logger log =
            LoggerFactory.getLogger(OAuthAuthorizationRevocationLogoutHandler.class);

    private final ServerOAuth2AuthorizedClientRepository authorizedClients;
    private final WebClient webClient;
    private final String revocationEndpoint;
    private final String logoutAuditEndpoint;

    public OAuthAuthorizationRevocationLogoutHandler(
            ServerOAuth2AuthorizedClientRepository authorizedClients,
            WebClient.Builder webClientBuilder,
            @Value("${minipay.identity-internal-url}") String identityInternalUrl) {
        this.authorizedClients = authorizedClients;
        this.webClient = webClientBuilder.build();
        this.revocationEndpoint = identityInternalUrl + "/oauth2/revoke";
        this.logoutAuditEndpoint = identityInternalUrl + "/api/v1/admin/logout-audits";
    }

    @Override
    public Mono<Void> logout(WebFilterExchange exchange, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)) {
            return Mono.empty();
        }
        String registrationId = oauth.getAuthorizedClientRegistrationId();
        return authorizedClients
                .<OAuth2AuthorizedClient>loadAuthorizedClient(
                        registrationId,
                        authentication,
                        exchange.getExchange())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("OAuth authorized client was unavailable during management logout");
                    return Mono.empty();
                }))
                .flatMap(client -> auditLogout(client)
                        .onErrorResume(error -> {
                            log.warn(
                                    "Logout audit delivery failed during management logout: {}",
                                    error.getClass().getSimpleName());
                            return Mono.empty();
                        })
                        .then(revoke(client))
                        .doOnSuccess(ignored -> log.info(
                                "Revoked OAuth authorization during management logout"))
                        .onErrorResume(error -> {
                            log.warn(
                                    "OAuth authorization revocation failed during management logout: {}",
                                    error.getClass().getSimpleName());
                            return Mono.empty();
                        })
                        .then(authorizedClients.removeAuthorizedClient(
                                registrationId,
                                authentication,
                                exchange.getExchange())))
                .then();
    }

    private Mono<Void> auditLogout(OAuth2AuthorizedClient client) {
        return webClient.post()
                .uri(logoutAuditEndpoint)
                .headers(headers -> headers.setBearerAuth(
                        client.getAccessToken().getTokenValue()))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .then();
    }

    private Mono<Void> revoke(OAuth2AuthorizedClient client) {
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        OAuth2AccessToken accessToken = client.getAccessToken();
        String token = refreshToken == null ? accessToken.getTokenValue() : refreshToken.getTokenValue();
        String hint = refreshToken == null ? "access_token" : "refresh_token";
        return webClient.post()
                .uri(revocationEndpoint)
                .headers(headers -> headers.setBasicAuth(
                        client.getClientRegistration().getClientId(),
                        client.getClientRegistration().getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("token", token).with("token_type_hint", hint))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .then();
    }
}
