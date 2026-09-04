package com.minipay.managementbff.interfaces.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.managementbff.infrastructure.security.RequestIdWebFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/login-audits")
public class LoginAuditProxyController {
    private final ReactiveOAuth2AuthorizedClientManager authorizedClients;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String identityUrl;

    public LoginAuditProxyController(
            ReactiveOAuth2AuthorizedClientManager authorizedClients,
            WebClient.Builder webClient,
            ObjectMapper objectMapper,
            @Value("${minipay.identity-internal-url}") String identityUrl) {
        this.authorizedClients = authorizedClients;
        this.webClient = webClient.build();
        this.objectMapper = objectMapper;
        this.identityUrl = identityUrl;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication,
            ServerWebExchange exchange) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("minipay-ops")
                .principal(authentication)
                .attribute(ServerWebExchange.class.getName(), exchange)
                .build();
        return authorizedClients.authorize(authorizeRequest)
                .switchIfEmpty(Mono.error(new IllegalStateException("OAuth session is unavailable")))
                .flatMap(client -> webClient.get()
                        .uri(identityUrl + "/api/v1/admin/login-audits?page={page}&size={size}", page, size)
                        .headers(headers -> headers.setBearerAuth(client.getAccessToken().getTokenValue()))
                        .header(RequestIdWebFilter.HEADER, RequestIdWebFilter.get(exchange))
                        .accept(MediaType.APPLICATION_JSON)
                        .exchangeToMono(response -> {
                            MediaType contentType = response.headers().contentType()
                                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
                            if (response.statusCode().isError()
                                    && !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType)) {
                                return response.releaseBody().thenReturn(upstreamProblem(exchange));
                            }
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> ResponseEntity.status(response.statusCode())
                                            .contentType(contentType)
                                            .body(body));
                        }))
                .onErrorResume(this::isUpstreamFailure, exception ->
                        Mono.just(upstreamProblem(exchange)));
    }

    private boolean isUpstreamFailure(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof WebClientRequestException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<String> upstreamProblem(ServerWebExchange exchange) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://docs.minipay.local/problems/upstream-unavailable");
        problem.put("title", "Bad Gateway");
        problem.put("status", HttpStatus.BAD_GATEWAY.value());
        problem.put("code", "UPSTREAM_UNAVAILABLE");
        problem.put("requestId", RequestIdWebFilter.get(exchange));
        problem.put("instance", exchange.getRequest().getPath().value());
        try {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(objectMapper.writeValueAsString(problem));
        } catch (JsonProcessingException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"title\":\"Bad Gateway\",\"status\":502,"
                            + "\"code\":\"UPSTREAM_UNAVAILABLE\"}");
        }
    }
}
