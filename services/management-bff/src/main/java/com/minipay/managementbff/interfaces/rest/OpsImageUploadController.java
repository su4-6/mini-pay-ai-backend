package com.minipay.managementbff.interfaces.rest;

import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/** Same-origin OSS upload for ops-web, avoiding browser OSS CORS requirements. */
@RestController
@RequestMapping("/api/v1/ops-image-uploads")
public class OpsImageUploadController {
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ReactiveOAuth2AuthorizedClientManager authorizedClients;
    private final WebClient payment;
    private final WebClient outbound;

    public OpsImageUploadController(
            ReactiveOAuth2AuthorizedClientManager authorizedClients,
            @Value("${minipay.payment-internal-url}") String paymentUrl,
            @Value("${OUTBOUND_HTTPS_PROXY:}") String outboundProxyUrl) {
        this.authorizedClients = authorizedClients;
        this.payment = WebClient.builder().baseUrl(paymentUrl).build();
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(20))
                .resolver(DefaultAddressResolverGroup.INSTANCE);
        if (outboundProxyUrl != null && !outboundProxyUrl.isBlank()) {
            java.net.URI proxyUri = java.net.URI.create(outboundProxyUrl.trim());
            if (!"http".equalsIgnoreCase(proxyUri.getScheme()) || proxyUri.getHost() == null) {
                throw new IllegalArgumentException("OUTBOUND_HTTPS_PROXY must be an HTTP proxy URL");
            }
            client = client.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                    .host(proxyUri.getHost()).port(proxyUri.getPort() < 0 ? 80 : proxyUri.getPort()));
        }
        this.outbound = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client)).build();
    }

    @PostMapping(consumes = {"image/jpeg", "image/png", "image/webp"})
    public Mono<UploadResponse> upload(
            @RequestHeader("X-File-Name") String encodedFileName,
            @RequestHeader("Content-Type") String contentType,
            @RequestBody Mono<byte[]> body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("minipay-ops")
                .principal(authentication)
                .attribute(ServerWebExchange.class.getName(), exchange)
                .build();
        return authorizedClients.authorize(authorizeRequest)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "OPS_REAUTHENTICATION_REQUIRED")))
                .flatMap(client -> body.flatMap(bytes -> upload(
                        client.getAccessToken().getTokenValue(), encodedFileName, contentType, bytes)));
    }

    Mono<UploadResponse> upload(
            String accessToken, String encodedFileName, String contentType, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "IMAGE_UPLOAD_INVALID"));
        }
        String fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(sha256(bytes));
        return payment.post().uri("/api/v1/ops/image-uploads")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "fileName", fileName,
                        "contentType", contentType,
                        "sizeBytes", bytes.length,
                        "sha256", sha256))
                .retrieve().bodyToMono(UploadGrant.class)
                .flatMap(grant -> outbound.put().uri(java.net.URI.create(grant.uploadUrl()))
                        .headers(headers -> grant.requiredHeaders().forEach(headers::set))
                        .bodyValue(bytes)
                        .retrieve().toBodilessEntity()
                        .onErrorMap(error -> new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY, "IMAGE_UPLOAD_UPSTREAM_UNAVAILABLE", error))
                        .thenReturn(new UploadResponse(grant.objectKey())));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record UploadResponse(String objectKey) { }

    private record UploadGrant(
            String uploadUrl, String objectKey,
            Map<String, String> requiredHeaders, Instant expiresAt) { }
}
