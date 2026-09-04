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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/** Same-origin image upload for merchant-web; avoids browser-to-OSS CORS dependencies. */
@RestController
@RequestMapping("/api/v1/merchant-image-uploads")
public class MerchantImageUploadController {
    private static final Logger LOG = LoggerFactory.getLogger(MerchantImageUploadController.class);
    private static final Pattern OSS_ERROR_CODE = Pattern.compile("<Code>([A-Za-z0-9]+)</Code>");
    private static final String ACCESS_TOKEN = "merchant.access-token";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "merchant.access-token-expires-at";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final WebClient payment;
    private final WebClient outbound;

    public MerchantImageUploadController(
            @Value("${minipay.payment-internal-url}") String paymentUrl,
            @Value("${OUTBOUND_HTTPS_PROXY:}") String outboundProxyUrl) {
        this.payment = WebClient.builder().baseUrl(paymentUrl).build();
        HttpClient outboundHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(20))
                .resolver(DefaultAddressResolverGroup.INSTANCE);
        if (outboundProxyUrl != null && !outboundProxyUrl.isBlank()) {
            java.net.URI proxyUri = java.net.URI.create(outboundProxyUrl.trim());
            if (!"http".equalsIgnoreCase(proxyUri.getScheme()) || proxyUri.getHost() == null) {
                throw new IllegalArgumentException("OUTBOUND_HTTPS_PROXY must be an HTTP proxy URL");
            }
            int proxyPort = proxyUri.getPort() < 0 ? 80 : proxyUri.getPort();
            outboundHttpClient = outboundHttpClient.proxy(proxy -> proxy
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(proxyUri.getHost())
                    .port(proxyPort));
        }
        this.outbound = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(outboundHttpClient))
                .build();
    }

    @PostMapping(consumes = {"image/jpeg", "image/png", "image/webp"})
    public Mono<UploadResponse> upload(
            @RequestHeader("X-File-Name") String encodedFileName,
            @RequestHeader("Content-Type") String contentType,
            @RequestBody Mono<byte[]> body,
            WebSession session) {
        String accessToken = requireAccessToken(session);
        return body.switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "IMAGE_UPLOAD_INVALID")))
                .flatMap(bytes -> {
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "IMAGE_UPLOAD_INVALID"));
            }
            String fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(sha256(bytes));
            return payment.post().uri("/api/v1/merchant/image-uploads")
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
                            .doOnError(error -> logUploadFailure(grant.uploadUrl(), error))
                            .onErrorMap(error -> new ResponseStatusException(
                                    HttpStatus.BAD_GATEWAY,
                                    "IMAGE_UPLOAD_UPSTREAM_UNAVAILABLE",
                                    error))
                            .thenReturn(new UploadResponse(grant.objectKey())));
                });
    }

    private static void logUploadFailure(String uploadUrl, Throwable error) {
        String host;
        try {
            host = java.net.URI.create(uploadUrl).getHost();
        } catch (RuntimeException ignored) {
            host = "invalid-uri";
        }
        Throwable cause = error.getCause();
        String providerCode = "none";
        int status = 0;
        if (error instanceof WebClientResponseException response) {
            status = response.getStatusCode().value();
            Matcher matcher = OSS_ERROR_CODE.matcher(response.getResponseBodyAsString());
            if (matcher.find()) {
                providerCode = matcher.group(1);
            }
        }
        LOG.warn("Merchant image OSS upload failed: host={}, status={}, providerCode={}, error={}, cause={}",
                host, status, providerCode,
                error.getClass().getSimpleName(),
                cause == null ? "none" : cause.getClass().getSimpleName());
    }

    private static String requireAccessToken(WebSession session) {
        String token = session.getAttribute(ACCESS_TOKEN);
        Instant expiresAt = session.getAttribute(ACCESS_TOKEN_EXPIRES_AT);
        if (token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "MERCHANT_REAUTHENTICATION_REQUIRED");
        }
        return token;
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
            String uploadUrl,
            String objectKey,
            Map<String, String> requiredHeaders,
            Instant expiresAt) { }
}
