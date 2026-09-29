package com.minipay.consumerbff.interfaces.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.consumerbff.application.service.ConsumerSessionService;
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/** Same-origin shop-image upload for the consumer merchant application form. */
@RestController
@RequestMapping("/api/v1/merchant-center/image-files")
public class ConsumerMerchantImageUploadController {
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ConsumerSessionService sessions;
    private final ObjectMapper objectMapper;
    private final WebClient outbound;

    public ConsumerMerchantImageUploadController(
            ConsumerSessionService sessions,
            ObjectMapper objectMapper,
            @Value("${OUTBOUND_HTTPS_PROXY:}") String outboundProxyUrl) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(20))
                .resolver(DefaultAddressResolverGroup.INSTANCE);
        if (outboundProxyUrl != null && !outboundProxyUrl.isBlank()) {
            java.net.URI proxyUri = java.net.URI.create(outboundProxyUrl.trim());
            if (!"http".equalsIgnoreCase(proxyUri.getScheme()) || proxyUri.getHost() == null) {
                throw new IllegalArgumentException("OUTBOUND_HTTPS_PROXY must be an HTTP proxy URL");
            }
            int port = proxyUri.getPort() < 0 ? 80 : proxyUri.getPort();
            http = http.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                    .host(proxyUri.getHost()).port(port));
        }
        this.outbound = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    @PostMapping(consumes = {"image/jpeg", "image/png", "image/webp"})
    public Mono<UploadResponse> upload(
            @RequestHeader("X-File-Name") String encodedFileName,
            @RequestHeader("Content-Type") String contentType,
            @RequestBody Mono<byte[]> body,
            WebSession session,
            ServerWebExchange exchange) {
        return body.switchIfEmpty(Mono.error(invalid()))
                .flatMap(bytes -> {
                    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                        return Mono.error(invalid());
                    }
                    String fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
                    return sessions.proxy(
                                    session,
                                    exchange,
                                    HttpMethod.POST,
                                    "/api/v1/consumer-merchant/image-uploads",
                                    Map.of(),
                                    Map.of(
                                            "fileName", fileName,
                                            "contentType", contentType,
                                            "sizeBytes", bytes.length,
                                            "sha256", HexFormat.of().formatHex(sha256(bytes))),
                                    UUID.randomUUID().toString())
                            .flatMap(response -> {
                                if (!response.successful()) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.valueOf(response.status()),
                                            "IMAGE_UPLOAD_GRANT_REJECTED"));
                                }
                                UploadGrant grant = parseGrant(response.body());
                                return outbound.put().uri(java.net.URI.create(grant.uploadUrl()))
                                        .headers(headers -> grant.requiredHeaders().forEach(headers::set))
                                        .bodyValue(bytes)
                                        .retrieve()
                                        .toBodilessEntity()
                                        .onErrorMap(error -> new ResponseStatusException(
                                                HttpStatus.BAD_GATEWAY,
                                                "IMAGE_UPLOAD_UPSTREAM_UNAVAILABLE", error))
                                        .thenReturn(new UploadResponse(grant.objectKey()));
                            });
                });
    }

    private UploadGrant parseGrant(String body) {
        try {
            Map<String, Object> document = objectMapper.readValue(
                    body, new TypeReference<Map<String, Object>>() { });
            String uploadUrl = requiredString(document, "uploadUrl");
            String objectKey = requiredString(document, "objectKey");
            @SuppressWarnings("unchecked")
            Map<String, String> requiredHeaders = (Map<String, String>) document.get("requiredHeaders");
            if (requiredHeaders == null) {
                throw new IllegalArgumentException("invalid upload grant");
            }
            return new UploadGrant(uploadUrl, objectKey, requiredHeaders);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "IMAGE_UPLOAD_GRANT_INVALID", exception);
        }
    }

    private static String requiredString(Map<String, Object> document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("invalid upload grant");
        }
        return text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMAGE_UPLOAD_INVALID");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record UploadResponse(String objectKey) { }

    private record UploadGrant(
            String uploadUrl, String objectKey, Map<String, String> requiredHeaders) { }
}
