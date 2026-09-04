package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.YshopFoodIntegrationService;
import com.minipay.commerce.application.YshopFoodIntegrationService.AddressView;
import com.minipay.commerce.application.YshopFoodIntegrationService.CartView;
import com.minipay.commerce.application.YshopFoodIntegrationService.OrderView;
import com.minipay.commerce.application.YshopFoodIntegrationService.ProductView;
import com.minipay.commerce.application.YshopFoodIntegrationService.QuoteView;
import com.minipay.commerce.application.YshopFoodIntegrationService.StoreView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v2/agent/food")
public class AgentFoodV2Controller {
    private final YshopFoodIntegrationService food;

    public AgentFoodV2Controller(YshopFoodIntegrationService food) {
        this.food = food;
    }

    @GetMapping("/stores")
    public List<StoreView> stores(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID locationContextId,
            @RequestParam(required = false) UUID addressRefId,
            @RequestParam(defaultValue = "TAKEOUT") String fulfillmentType) {
        return food.stores(userId(jwt), locationContextId, addressRefId, fulfillmentType);
    }

    @GetMapping("/stores/{storeRefId}/menu")
    public List<ProductView> menu(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID storeRefId) {
        return food.menu(userId(jwt), storeRefId);
    }

    @GetMapping("/carts/{storeRefId}")
    public CartView cart(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID storeRefId) {
        return food.cart(userId(jwt), storeRefId);
    }

    @PutMapping("/carts/{storeRefId}/items/{skuRefId}")
    public CartView updateCart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID storeRefId,
            @PathVariable UUID skuRefId,
            @Valid @RequestBody UpdateCartRequest request) {
        return food.updateCart(userId(jwt), storeRefId, skuRefId,
                request.quantity(), request.expectedVersion());
    }

    @GetMapping("/addresses")
    public List<AddressView> addresses(@AuthenticationPrincipal Jwt jwt) {
        return food.addresses(userId(jwt));
    }

    @PostMapping("/checkout-quotes")
    public QuoteView quote(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PrepareQuoteRequest request) {
        return food.prepareQuote(userId(jwt), request.storeRefId(), request.addressRefId(),
                request.fulfillmentType());
    }

    @GetMapping("/orders/{orderRefId}")
    public OrderView order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderRefId) {
        return food.order(userId(jwt), orderRefId);
    }

    @GetMapping("/orders/{orderRefId}/cancellation-preview")
    public CancellationPreview cancellationPreview(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderRefId) {
        OrderView order = food.order(userId(jwt), orderRefId);
        boolean unpaid = "UNPAID".equals(order.paymentStatus()) || "FAILED".equals(order.paymentStatus());
        boolean cancellable = unpaid || "PAID".equals(order.paymentStatus());
        return new CancellationPreview(order.orderRefId(), order.externalOrderNo(),
                unpaid ? "CANCEL" : "FULL_REFUND", cancellable,
                order.amountCent(), order.currency(), Instant.now().plusSeconds(300));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    public record UpdateCartRequest(@Min(0) @Max(99) int quantity, Long expectedVersion) { }
    public record PrepareQuoteRequest(
            @NotNull UUID storeRefId, UUID addressRefId, @NotNull String fulfillmentType) { }
    public record CancellationPreview(
            UUID orderRefId, String externalOrderNo, String action, boolean cancellable,
            long amountCent, String currency, Instant expiresAt) { }
}
