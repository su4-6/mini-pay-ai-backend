package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Selected same-origin merchant operations for the consumer H5. */
@RestController
@RequestMapping("/api/v1/merchant-center")
public class ConsumerMerchantProxyController {
    private final ConsumerSessionService sessions;

    public ConsumerMerchantProxyController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/merchants")
    public Mono<ResponseEntity<String>> merchants(
            WebSession session, ServerWebExchange exchange) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/consumer-merchant/merchants", Map.of(), null, null);
    }

    @GetMapping("/onboardings")
    public Mono<ResponseEntity<String>> onboardings(
            WebSession session,
            ServerWebExchange exchange,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/consumer-merchant/onboardings",
                Map.of("page", page, "size", size), null, null);
    }

    @PostMapping("/onboardings")
    public Mono<ResponseEntity<String>> submit(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        return proxy(session, exchange, HttpMethod.POST,
                "/api/v1/consumer-merchant/onboardings", Map.of(), body,
                idempotencyKey(idempotencyKey));
    }

    @PutMapping("/onboardings/{applyId}")
    public Mono<ResponseEntity<String>> resubmit(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable @Pattern(regexp = "^\\d{1,19}$") String applyId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        return proxy(session, exchange, HttpMethod.PUT,
                "/api/v1/consumer-merchant/onboardings/" + applyId, Map.of(), body,
                idempotencyKey(idempotencyKey));
    }

    @PostMapping("/merchants/{merchantId}/initialization")
    public Mono<ResponseEntity<String>> initialize(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable @NotBlank @Size(max = 36) String merchantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID.fromString(merchantId);
        return proxy(session, exchange, HttpMethod.POST,
                "/api/v1/consumer-merchant/merchants/" + merchantId + "/initialization",
                Map.of(), Map.of(), idempotencyKey(idempotencyKey));
    }

    @GetMapping("/collection-code")
    public Mono<ResponseEntity<String>> collectionCode(
            WebSession session, ServerWebExchange exchange) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/consumer-merchant/collection-code", Map.of(), null, null);
    }

    @PostMapping("/image-uploads")
    public Mono<ResponseEntity<String>> createImageUpload(
            WebSession session,
            ServerWebExchange exchange,
            @RequestBody Map<String, Object> body) {
        return proxy(session, exchange, HttpMethod.POST,
                "/api/v1/consumer-merchant/image-uploads", Map.of(), body,
                idempotencyKey(null));
    }

    private Mono<ResponseEntity<String>> proxy(
            WebSession session,
            ServerWebExchange exchange,
            HttpMethod method,
            String path,
            Map<String, String> query,
            Object body,
            String idempotencyKey) {
        return sessions.proxy(session, exchange, method, path, query, body, idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    private static String idempotencyKey(String supplied) {
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }
}
