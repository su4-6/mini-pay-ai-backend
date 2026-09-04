package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.YshopFoodIntegrationService;
import com.minipay.commerce.application.YshopFoodIntegrationService.BindingView;
import com.minipay.commerce.application.YshopFoodIntegrationService.HandoffView;
import com.minipay.commerce.application.YshopFoodIntegrationService.LocationView;
import com.minipay.commerce.application.YshopFoodIntegrationService.EntryStatusView;
import com.minipay.commerce.application.YshopFoodIntegrationService.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce")
public class YshopFoodConsumerController {
    private final YshopFoodIntegrationService food;

    public YshopFoodConsumerController(YshopFoodIntegrationService food) {
        this.food = food;
    }

    @GetMapping("/food-binding")
    public BindingView binding(@AuthenticationPrincipal Jwt jwt) {
        return food.binding(userId(jwt));
    }

    @GetMapping("/food-entry-status")
    public EntryStatusView entryStatus(@AuthenticationPrincipal Jwt jwt) {
        return food.entryStatus(userId(jwt));
    }

    @PostMapping("/food-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public BindingView bind(@AuthenticationPrincipal Jwt jwt) {
        return food.bind(userId(jwt));
    }

    @DeleteMapping("/food-binding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@AuthenticationPrincipal Jwt jwt) {
        food.unbind(userId(jwt));
    }

    @PostMapping("/food-handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffView handoff(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Device-Id") String deviceId) {
        return food.issueHandoff(userId(jwt), deviceId);
    }

    @PostMapping("/food-location-contexts")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationView location(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LocationRequest request) {
        return food.createLocation(userId(jwt), request.longitude(), request.latitude(),
                request.accuracyMeters(), request.capturedAt(), request.source());
    }

    @GetMapping("/food-orders")
    public List<OrderView> orders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return food.listOrders(userId(jwt), page, size);
    }

    @GetMapping("/food-orders/{orderId}")
    public OrderView order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return food.order(userId(jwt), orderId);
    }

    @PostMapping("/food-checkouts/{quoteId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID quoteId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) CreateFoodOrderRequest request) {
        return food.createOrder(userId(jwt), quoteId, idempotencyKey,
                request == null ? null : request.remark());
    }

    @PostMapping("/food-orders/{externalOrderNo}/payment-orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderView preparePayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String externalOrderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return food.preparePaymentForExternalOrder(
                userId(jwt), externalOrderNo, idempotencyKey);
    }

    @PostMapping("/food-orders/{orderId}/cancellation-requests")
    public OrderView cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) CancellationRequest request) {
        return food.requestCancellation(userId(jwt), orderId,
                request == null ? null : request.reason());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    public record LocationRequest(
            double longitude,
            double latitude,
            @Min(0) @Max(10000) Double accuracyMeters,
            @NotNull Instant capturedAt,
            String source) { }
    public record CreateFoodOrderRequest(String remark) { }
    public record CancellationRequest(String reason) { }
}
