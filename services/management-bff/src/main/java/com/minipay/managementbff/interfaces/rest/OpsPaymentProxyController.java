package com.minipay.managementbff.interfaces.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.managementbff.infrastructure.security.RequestIdWebFilter;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class OpsPaymentProxyController {
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(4);

    private final ReactiveOAuth2AuthorizedClientManager authorizedClients;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String paymentUrl;
    private final String walletUrl;
    private final String commerceUrl;

    public OpsPaymentProxyController(
            ReactiveOAuth2AuthorizedClientManager authorizedClients,
            WebClient.Builder webClient,
            ObjectMapper objectMapper,
            @Value("${minipay.payment-internal-url}") String paymentUrl,
            @Value("${minipay.wallet-internal-url}") String walletUrl,
            @Value("${minipay.commerce-internal-url}") String commerceUrl) {
        this.authorizedClients = authorizedClients;
        this.webClient = webClient.build();
        this.objectMapper = objectMapper;
        this.paymentUrl = paymentUrl;
        this.walletUrl = walletUrl;
        this.commerceUrl = commerceUrl;
    }

    @RequestMapping("/api/v1/ops/**")
    public Mono<ResponseEntity<byte[]>> proxy(
            Authentication authentication,
            ServerWebExchange exchange) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("minipay-ops")
                .principal(authentication)
                .attribute(ServerWebExchange.class.getName(), exchange)
                .build();
        return authorizedClients.authorize(authorizeRequest)
                .flatMap(client -> forward(client, exchange))
                .switchIfEmpty(Mono.just(authRequired(exchange)))
                .timeout(UPSTREAM_TIMEOUT)
                .onErrorResume(this::isUpstreamFailure,
                        exception -> Mono.just(upstreamProblem(exchange)));
    }

    private Mono<ResponseEntity<byte[]>> forward(
            OAuth2AuthorizedClient client, ServerWebExchange exchange) {
        String rawQuery = exchange.getRequest().getURI().getRawQuery();
        String path = exchange.getRequest().getPath().value();
        String targetPath = path;
        String targetUrl = paymentUrl;
        if (path.startsWith("/api/v1/ops/food-orders")) {
            targetUrl = commerceUrl;
        } else if (path.startsWith("/api/v1/ops/collection-records")) {
            targetUrl = walletUrl;
            targetPath = path.replaceFirst("/api/v1/ops/collection-records",
                    "/api/v1/management/collection-records");
        }
        String uri = targetUrl + targetPath
                + (rawQuery == null ? "" : "?" + rawQuery);
        WebClient.RequestBodySpec request = webClient
                .method(exchange.getRequest().getMethod())
                .uri(URI.create(uri))
                .headers(headers -> {
                    headers.setBearerAuth(client.getAccessToken().getTokenValue());
                    headers.set(RequestIdWebFilter.HEADER, RequestIdWebFilter.get(exchange));
                    copy(exchange, headers, HttpHeaders.ACCEPT);
                    copy(exchange, headers, HttpHeaders.CONTENT_TYPE);
                    copy(exchange, headers, "Idempotency-Key");
                    copy(exchange, headers, HttpHeaders.IF_MATCH);
                });
        return request.body(exchange.getRequest().getBody(), org.springframework.core.io.buffer.DataBuffer.class)
                .exchangeToMono(response -> response.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(body -> {
                            ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode());
                            response.headers().contentType().ifPresent(builder::contentType);
                            String etag = response.headers().asHttpHeaders().getETag();
                            if (etag != null) {
                                builder.eTag(etag);
                            }
                            return builder.body(body);
                        }));
    }

    private static void copy(ServerWebExchange exchange, HttpHeaders target, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value != null) {
            target.set(name, value);
        }
    }

    private boolean isUpstreamFailure(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof WebClientRequestException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<byte[]> upstreamProblem(ServerWebExchange exchange) {
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
                    .body(objectMapper.writeValueAsBytes(problem));
        } catch (JsonProcessingException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"title\":\"Bad Gateway\",\"status\":502,"
                            .concat("\"code\":\"UPSTREAM_UNAVAILABLE\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private ResponseEntity<byte[]> authRequired(ServerWebExchange exchange) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://docs.minipay.local/problems/authentication-required");
        problem.put("title", "Unauthorized");
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("code", "AUTHENTICATION_REQUIRED");
        problem.put("requestId", RequestIdWebFilter.get(exchange));
        problem.put("instance", exchange.getRequest().getPath().value());
        try {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(objectMapper.writeValueAsBytes(problem));
        } catch (JsonProcessingException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"title\":\"Unauthorized\",\"status\":401,"
                            .concat("\"code\":\"AUTHENTICATION_REQUIRED\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
