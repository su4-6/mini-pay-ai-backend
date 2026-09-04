package com.minipay.managementbff.interfaces.rest;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Same-origin relay for merchant-web; the browser never receives the access token. */
@RestController
@RequestMapping("/api/v1/merchant-gateway")
public class MerchantPaymentProxyController {
    private static final String ACCESS_TOKEN = "merchant.access-token";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "merchant.access-token-expires-at";
    private final WebClient payment;

    public MerchantPaymentProxyController(@Value("${minipay.payment-internal-url}") String paymentUrl) {
        this.payment = WebClient.builder().baseUrl(paymentUrl).build();
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<String>> relay(ServerHttpRequest request, @RequestBody(required = false) Mono<String> body,
            WebSession session) {
        String accessToken = session.getAttribute(ACCESS_TOKEN);
        Instant expiresAt = session.getAttribute(ACCESS_TOKEN_EXPIRES_AT);
        if (accessToken == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MERCHANT_REAUTHENTICATION_REQUIRED"));
        }
        String requestPath = request.getURI().getRawPath();
        String prefix = "/api/v1/merchant-gateway";
        String targetPath = "/api/v1" + requestPath.substring(prefix.length());
        String query = request.getURI().getRawQuery();
        if (query != null && !query.isBlank()) targetPath += "?" + query;
        String resolvedTargetPath = targetPath;
        return body.defaultIfEmpty("").flatMap(content -> payment.method(request.getMethod()).uri(resolvedTargetPath)
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    String requestId = request.getHeaders().getFirst("X-Request-Id");
                    if (requestId != null) headers.set("X-Request-Id", requestId);
                    String idempotencyKey = request.getHeaders().getFirst("Idempotency-Key");
                    if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
                    MediaType contentType = request.getHeaders().getContentType();
                    if (contentType != null) headers.setContentType(contentType);
                })
                .bodyValue(content)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(responseBody -> ResponseEntity.status(response.statusCode())
                                .header(HttpHeaders.CONTENT_TYPE, response.headers().contentType()
                                        .map(MediaType::toString).orElse(MediaType.APPLICATION_JSON_VALUE))
                                .body(responseBody))));
    }
}
