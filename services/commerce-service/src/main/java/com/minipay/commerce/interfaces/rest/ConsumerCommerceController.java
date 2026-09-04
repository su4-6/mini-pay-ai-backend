package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.CommerceCheckoutService;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.domain.model.FoodOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce/orders")
public class ConsumerCommerceController {
    private final CommerceCheckoutService checkout;
    private final CommerceOrderService orders;

    public ConsumerCommerceController(CommerceCheckoutService checkout, CommerceOrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FoodOrder create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        return checkout.createOrder(userId(jwt), request.quoteId(), idempotencyKey);
    }

    @PostMapping("/{orderId}/cancel")
    public FoodOrder cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.cancel(userId(jwt), orderId);
    }


    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    public record CreateOrderRequest(@NotNull UUID quoteId) {
    }

}
