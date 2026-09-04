package com.minipay.agent.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.minipay.agent.application.port.DelegatedTokenProvider;
import com.minipay.agent.application.service.AgentApplicationException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public final class IdentityDelegatedTokenProvider implements DelegatedTokenProvider {
    private static final String TOKEN_EXCHANGE_GRANT =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";

    private final WebClient webClient;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final Clock clock;

    @Autowired
    public IdentityDelegatedTokenProvider(
            WebClient.Builder webClientBuilder,
            @Value("${minipay.agent.identity.token-url}") String tokenUrl,
            @Value("${minipay.agent.identity.client-id}") String clientId,
            @Value("${minipay.agent.identity.client-secret}") String clientSecret,
            @Value("${minipay.agent.identity.exchange-timeout:5s}") Duration timeout) {
        this(webClientBuilder.build(), tokenUrl, clientId, clientSecret, timeout, Clock.systemUTC());
    }

    IdentityDelegatedTokenProvider(
            WebClient webClient,
            String tokenUrl,
            String clientId,
            String clientSecret,
            Duration timeout,
            Clock clock) {
        this.webClient = webClient;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public DelegatedToken exchange(TokenExchangeRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", TOKEN_EXCHANGE_GRANT);
        form.add("subject_token", request.subjectToken());
        form.add("subject_token_type", ACCESS_TOKEN_TYPE);
        form.add("requested_token_type", ACCESS_TOKEN_TYPE);
        form.add("audience", request.audience());
        form.add("scope", String.join(" ", request.scopes()));
        form.add("purpose", request.purpose());
        form.add("run_id", request.runId().toString());
        try {
            TokenResponse response = webClient.post()
                    .uri(tokenUrl)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .timeout(timeout)
                    .block();
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                    || response.expiresIn() <= 0) {
                throw new AgentApplicationException(
                        "AGENT_TOKEN_EXCHANGE_INVALID_RESPONSE", "用户授权服务返回异常");
            }
            Set<String> scopes = response.scope() == null || response.scope().isBlank()
                    ? Set.of()
                    : Set.copyOf(Arrays.asList(response.scope().split(" +")));
            return new DelegatedToken(
                    response.accessToken(),
                    response.tokenType(),
                    clock.instant().plusSeconds(response.expiresIn()),
                    scopes);
        } catch (WebClientResponseException exception) {
            throw new AgentApplicationException(
                    "AGENT_TOKEN_EXCHANGE_REJECTED", "用户授权已失效，请重新进入任务");
        } catch (AgentApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentApplicationException(
                    "AGENT_TOKEN_EXCHANGE_UNAVAILABLE", "用户授权服务暂时不可用，请稍后重试");
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope) {
    }
}
