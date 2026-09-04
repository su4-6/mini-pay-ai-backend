package com.minipay.managementbff.interfaces.rest;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Same-user, read-only wallet relay for merchant web. Wallet write routes are deliberately not exposed. */
@RestController
@RequestMapping("/api/v1/merchant-wallet")
public class MerchantWalletProxyController {
    private static final String ACCESS_TOKEN = "merchant.access-token";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "merchant.access-token-expires-at";
    private final WebClient wallet;

    public MerchantWalletProxyController(@Value("${minipay.wallet-internal-url}") String walletUrl) {
        this.wallet = WebClient.builder().baseUrl(walletUrl).build();
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<String>> relay(ServerHttpRequest request, WebSession session) {
        if (!"GET".equalsIgnoreCase(request.getMethod().name())) {
            return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "MERCHANT_WALLET_READ_ONLY"));
        }
        String accessToken = session.getAttribute(ACCESS_TOKEN);
        Instant expiresAt = session.getAttribute(ACCESS_TOKEN_EXPIRES_AT);
        if (accessToken == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MERCHANT_REAUTHENTICATION_REQUIRED"));
        }
        String prefix = "/api/v1/merchant-wallet";
        String targetPath = "/api/v1/wallets/me" + request.getURI().getRawPath().substring(prefix.length());
        String query = request.getURI().getRawQuery();
        if (query != null && !query.isBlank()) targetPath += "?" + query;
        return wallet.get().uri(targetPath).headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    String requestId = request.getHeaders().getFirst("X-Request-Id");
                    if (requestId != null) headers.set("X-Request-Id", requestId);
                }).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> ResponseEntity.status(response.statusCode())
                                .header(HttpHeaders.CONTENT_TYPE, response.headers().contentType()
                                        .map(MediaType::toString).orElse(MediaType.APPLICATION_JSON_VALUE))
                                .body(body)));
    }
}
