package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PaymentOrderService;
import com.minipay.payment.domain.model.PaymentOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/channel-callbacks/sandbox/payment-orders")
public class InternalChannelCallbackController {
    private final PaymentOrderService payments;

    public InternalChannelCallbackController(PaymentOrderService payments) {
        this.payments = payments;
    }

    @PostMapping("/{paymentOrderId}")
    public PaymentOrder complete(
            @PathVariable UUID paymentOrderId,
            @Valid @RequestBody CallbackRequest request) {
        return payments.completeTrustedSandboxCallback(paymentOrderId, request.succeeded());
    }

    public record CallbackRequest(@NotNull Boolean succeeded) {
    }
}
