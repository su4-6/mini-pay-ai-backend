package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.TransferService;
import com.minipay.payment.domain.model.TransferIntent;
import com.minipay.payment.domain.model.TransferOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent")
public class AgentPaymentController {
    private final TransferService transfers;

    public AgentPaymentController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/transfer-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferIntent prepareTransfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PrepareTransferRequest request) {
        return transfers.create(
                UUID.fromString(jwt.getClaimAsString("user_id")),
                idempotencyKey,
                request.receiverUserId(),
                request.amountCent(),
                request.remark(),
                "AI");
    }

    @GetMapping("/transfer-orders/{transferId}")
    public TransferOrder getTransferOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transferId) {
        return transfers.getOrder(UUID.fromString(jwt.getClaimAsString("user_id")), transferId);
    }

    public record PrepareTransferRequest(
            @NotNull UUID receiverUserId,
            @Min(1) @Max(1_000_000) long amountCent,
            @Size(max = 50) String remark) {
    }
}
