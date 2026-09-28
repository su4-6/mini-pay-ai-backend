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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Sandbox money movement. The three-step transfer flow (prepare, authorize, confirm) is preserved
 * exactly as upstream: the BFF never merges the payment-password exchange into the confirmation
 * call, and cancelling an intent stays a separate DELETE.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class TransferProxyController {

    private final ConsumerSessionService sessions;

    public TransferProxyController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/transfers/prepare")
    public Mono<ResponseEntity<Map<String, Object>>> prepare(
            WebSession session,
            ServerWebExchange exchange,
            @Valid @RequestBody PrepareTransferRequest request) {
        return sessions.prepareTransfer(
                        session,
                        exchange,
                        request.payeeIdentifier(),
                        request.amountFen(),
                        request.remark(),
                        RequestIds.of(exchange))
                .map(preparation -> UpstreamResponses.prepared(
                        preparation.transferIntentId(),
                        preparation.payeeMasked(),
                        preparation.amountFen(),
                        preparation.expiresAt()));
    }

    @PostMapping("/transfers/{intentId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String intentId,
            @Valid @RequestBody ConfirmTransferRequest request) {
        return sessions.confirmTransfer(
                        session,
                        exchange,
                        intentId,
                        request.amountFen(),
                        request.paymentPassword(),
                        RequestIds.of(exchange))
                .map(confirmation -> UpstreamResponses.confirmed(
                        confirmation.transferNo(),
                        confirmation.status(),
                        confirmation.failureCode()));
    }

    @DeleteMapping("/transfers/{intentId}")
    public Mono<ResponseEntity<String>> cancel(
            WebSession session, ServerWebExchange exchange, @PathVariable String intentId) {
        return sessions.cancelTransfer(session, exchange, intentId)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/transfers")
    public Mono<ResponseEntity<String>> history(
            WebSession session,
            ServerWebExchange exchange,
            @RequestParam(required = false) String counterpartyUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return sessions.transferHistory(session, exchange, counterpartyUserId, cursor, limit)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/transfers/{transferNo}")
    public Mono<ResponseEntity<String>> detail(
            WebSession session, ServerWebExchange exchange, @PathVariable String transferNo) {
        return sessions.transferOrder(session, exchange, transferNo)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/funding-orders")
    public Mono<ResponseEntity<String>> fundingOrders(
            WebSession session,
            ServerWebExchange exchange,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        boolean withdrawal = "WITHDRAWAL".equalsIgnoreCase(type);
        String path = withdrawal ? "/api/v1/withdrawal-orders" : "/api/v1/recharge-orders";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", cursor == null || cursor.isBlank() ? "1" : cursor);
        query.put("size", limit == null || limit.isBlank() ? "20" : limit);
        return sessions.proxy(session, exchange, HttpMethod.GET, path, query, null, null)
                .map(UpstreamResponses::toEntity);
    }

    public record PrepareTransferRequest(
            @NotBlank @Size(max = 128) String payeeIdentifier,
            @Min(1) @Max(1_000_000) long amountFen,
            @Size(max = 50) String remark) {
    }

    /**
     * {@code amountFen} is required and must be positive: it bounds the one-time payment
     * authorization, so a missing field must fail loudly instead of defaulting to zero.
     */
    public record ConfirmTransferRequest(
            @Min(1) @Max(1_000_000) long amountFen,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String paymentPassword) {
    }

}
