package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PaymentOrderService;
import com.minipay.payment.domain.model.PaymentOrder;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/food-orders/{foodOrderId}/payment-order")
public class FoodPaymentController {
    private final PaymentOrderService payments;

    public FoodPaymentController(PaymentOrderService payments) {
        this.payments = payments;
    }

    @GetMapping
    public PaymentOrder get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID foodOrderId) {
        return payments.getFoodPayment(
                ConsumerClaims.requireReadyUser(jwt, false), foodOrderId);
    }
}
