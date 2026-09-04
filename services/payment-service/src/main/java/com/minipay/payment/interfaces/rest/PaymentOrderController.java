package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PaymentOrderService;
import com.minipay.payment.domain.model.PaymentOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-orders")
public class PaymentOrderController {
    private final PaymentOrderService payments;

    public PaymentOrderController(PaymentOrderService payments) {
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrder create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request) {
        return payments.create(
                ConsumerClaims.requireReadyUser(jwt, true),
                idempotencyKey,
                request.amountCent(),
                request.subject(),
                request.paymentMethod(),
                request.resolutionId());
    }

    @GetMapping("/{paymentOrderId}")
    public PaymentOrder get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentOrderId) {
        return payments.get(
                ConsumerClaims.requireReadyUser(jwt, false), paymentOrderId);
    }

    @PostMapping("/{paymentOrderId}/confirm")
    public PaymentOrder confirmWalletBalance(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentOrderId,
            @Valid @RequestBody ConfirmPaymentRequest request) {
        return payments.confirmWalletBalance(
                ConsumerClaims.requireReadyUser(jwt, true),
                paymentOrderId,
                request.paymentAuthToken(),
                ConsumerClaims.requireDeviceId(jwt));
    }

    public record CreatePaymentOrderRequest(
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank @Size(max = 256) String subject,
            @NotBlank String paymentMethod,
            UUID resolutionId) {
    }

    public record ConfirmPaymentRequest(@NotBlank String paymentAuthToken) {
    }
}
