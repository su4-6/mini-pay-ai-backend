package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.TransferService;
import com.minipay.payment.domain.model.TransferIntent;
import com.minipay.payment.domain.model.TransferOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferIntent create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        return transfers.create(
                ConsumerClaims.requireReadyUser(jwt, true),
                idempotencyKey,
                request.receiverUserId(),
                request.amountCent(),
                request.remark(),
                request.source() == null ? "FORM" : request.source());
    }

    @PostMapping("/transfers/{intentId}/confirm")
    public ResponseEntity<TransferOrder> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID intentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmTransferRequest request) {
        TransferOrder order = transfers.confirm(
                ConsumerClaims.requireReadyUser(jwt, true),
                intentId,
                idempotencyKey,
                request.paymentAuthToken(),
                ConsumerClaims.requireDeviceId(jwt));
        return ResponseEntity.status("PROCESSING".equals(order.status())
                        ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(order);
    }

    @DeleteMapping("/transfers/{intentId}")
    public TransferIntent cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID intentId) {
        return transfers.cancel(
                ConsumerClaims.requireReadyUser(jwt, true), intentId);
    }

    @GetMapping("/transfer-orders/{transferId}")
    public TransferOrder get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transferId) {
        return transfers.getOrder(
                ConsumerClaims.requireReadyUser(jwt, false), transferId);
    }

    public record CreateTransferRequest(
            @NotNull UUID receiverUserId,
            @Min(1) @Max(1_000_000) long amountCent,
            @Size(max = 50) String remark,
            String source) {
    }

    public record ConfirmTransferRequest(@NotBlank String paymentAuthToken) {
    }
}
