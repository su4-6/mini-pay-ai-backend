package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Bank-card and funding operations that keep payment authorization inside the BFF. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class BankFundingController {

    private final ConsumerSessionService sessions;

    public BankFundingController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/bank-cards")
    public Mono<ResponseEntity<String>> bindCard(
            WebSession session,
            ServerWebExchange exchange,
            @Valid @RequestBody BindCardRequest request) {
        return proxy(session, exchange, HttpMethod.POST, "/api/v1/bank-cards", Map.of(),
                Map.of(
                        "holderName", request.holderName(),
                        "cardNumber", request.cardNumber(),
                        "verificationCode", request.verificationCode()),
                null);
    }

    @GetMapping("/bank-cards/{cardId}")
    public Mono<ResponseEntity<String>> card(
            WebSession session, ServerWebExchange exchange, @PathVariable String cardId) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/bank-cards/" + cardId, Map.of(), null, null);
    }

    @DeleteMapping("/bank-cards/{cardId}")
    public Mono<ResponseEntity<String>> unbindCard(
            WebSession session, ServerWebExchange exchange, @PathVariable String cardId) {
        return proxy(session, exchange, HttpMethod.DELETE,
                "/api/v1/bank-cards/" + cardId, Map.of(), null, null);
    }

    @GetMapping("/bank-cards/{cardId}/payment-limits")
    public Mono<ResponseEntity<String>> paymentLimits(
            WebSession session, ServerWebExchange exchange, @PathVariable String cardId) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/bank-cards/" + cardId + "/payment-limits", Map.of(), null, null);
    }

    @GetMapping("/bank-cards/{cardId}/transactions")
    public Mono<ResponseEntity<String>> transactions(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String cardId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String size) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", from);
        query.put("to", to);
        query.put("page", page);
        query.put("size", size);
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/bank-cards/" + cardId + "/transactions", query, null, null);
    }

    @PostMapping("/bank-cards/{cardId}/balance-queries")
    public Mono<ResponseEntity<String>> balance(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String cardId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody PaymentPasswordRequest request) {
        return sessions.queryBankBalance(
                        session, exchange, cardId, request.paymentPassword(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/recharge-orders")
    public Mono<ResponseEntity<String>> recharge(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        return sessions.recharge(session, exchange, request.bankCardId(), request.amountFen(),
                        request.paymentPassword(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/withdrawal-orders")
    public Mono<ResponseEntity<String>> withdraw(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        return sessions.withdraw(session, exchange, request.bankCardId(), request.amountFen(),
                        request.paymentPassword(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
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

    public record BindCardRequest(
            @NotBlank @Size(max = 64) String holderName,
            @NotBlank @Pattern(regexp = "^[0-9 ]{16,23}$") String cardNumber,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String verificationCode) {
    }

    public record PaymentPasswordRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$") String paymentPassword) {
    }

    public record FundingRequest(
            @NotBlank String bankCardId,
            @Min(1) @Max(1_000_000) long amountFen,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String paymentPassword) {
    }
}
