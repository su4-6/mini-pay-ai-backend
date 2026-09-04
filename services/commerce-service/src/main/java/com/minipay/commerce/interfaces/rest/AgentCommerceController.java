package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.CommerceCartService;
import com.minipay.commerce.application.CommerceCatalogService;
import com.minipay.commerce.application.CommerceCheckoutService;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.domain.model.CartSnapshot;
import com.minipay.commerce.domain.model.CatalogView;
import com.minipay.commerce.domain.model.CheckoutQuote;
import com.minipay.commerce.domain.model.FoodOrder;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent")
public class AgentCommerceController {
    private final CommerceCatalogService catalog;
    private final CommerceCartService carts;
    private final CommerceCheckoutService checkout;
    private final CommerceOrderService orders;

    public AgentCommerceController(
            CommerceCatalogService catalog,
            CommerceCartService carts,
            CommerceCheckoutService checkout,
            CommerceOrderService orders) {
        this.catalog = catalog;
        this.carts = carts;
        this.checkout = checkout;
        this.orders = orders;
    }

    @GetMapping("/merchants")
    public List<CatalogView.Merchant> searchMerchants(
            @RequestParam String zoneCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Long maxDeliveryFeeCent,
            @RequestParam(required = false) Integer maxDeliveryMinutes,
            @RequestParam(defaultValue = "10") int limit) {
        return catalog.search(zoneCode, categoryCode, maxDeliveryFeeCent, maxDeliveryMinutes, limit);
    }

    @GetMapping("/merchants/{merchantId}/menu")
    public List<CatalogView.MenuItem> menu(@PathVariable UUID merchantId) {
        return catalog.menu(merchantId);
    }

    @GetMapping("/carts/{merchantId}")
    public CartSnapshot getCart(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID merchantId) {
        return carts.get(userId(jwt), merchantId);
    }

    @PutMapping("/carts/{merchantId}/items/{skuId}")
    public CartSnapshot updateCart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable UUID skuId,
            @Valid @RequestBody UpdateCartRequest request) {
        return carts.update(userId(jwt), merchantId, skuId, request.quantity(),
                request.optionIds(), request.expectedCartVersion());
    }

    @PostMapping("/checkout-quotes")
    public CheckoutQuote prepareQuote(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PrepareQuoteRequest request) {
        return checkout.prepareQuote(userId(jwt), request.merchantId(), request.addressId(),
                request.expectedCartVersion(), idempotencyKey);
    }

    @GetMapping("/orders/{orderId}")
    public FoodOrder getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.get(userId(jwt), orderId);
    }

    @GetMapping("/orders/{orderId}/cancellation-preview")
    public CancellationPreview cancellationPreview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId) {
        FoodOrder order = orders.get(userId(jwt), orderId);
        boolean cancellable = order.status() == FoodOrderStatus.PENDING_PAYMENT
                || order.status() == FoodOrderStatus.PAID
                || order.status() == FoodOrderStatus.REFUND_FAILED;
        String action = order.status() == FoodOrderStatus.PENDING_PAYMENT
                ? "CANCEL" : "FULL_REFUND";
        return new CancellationPreview(order.id(), order.orderNo(), order.status().name(),
                action, cancellable, order.payableAmountCent(), "CNY",
                Instant.now().plusSeconds(300));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    public record UpdateCartRequest(
            @Min(0) @Max(99) int quantity,
            Set<UUID> optionIds,
            Long expectedCartVersion) {
    }

    public record PrepareQuoteRequest(
            @NotNull UUID merchantId,
            @NotNull UUID addressId,
            @Min(0) long expectedCartVersion) {
    }

    public record CancellationPreview(
            UUID orderId,
            String orderNo,
            String status,
            String action,
            boolean cancellable,
            long amountCent,
            String currency,
            Instant expiresAt) {
    }
}
