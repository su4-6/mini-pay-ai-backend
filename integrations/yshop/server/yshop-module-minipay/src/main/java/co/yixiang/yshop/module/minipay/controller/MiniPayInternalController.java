package co.yixiang.yshop.module.minipay.controller;

import static co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.*;

import co.yixiang.yshop.module.minipay.service.MiniPayFoodService;
import co.yixiang.yshop.module.minipay.service.MiniPaySessionService;
import co.yixiang.yshop.module.minipay.service.MiniPaySessionService.RevokeView;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/minipay/v1")
public class MiniPayInternalController {
    private final MiniPayFoodService food;
    private final MiniPaySessionService sessions;

    public MiniPayInternalController(MiniPayFoodService food, MiniPaySessionService sessions) {
        this.food = food;
        this.sessions = sessions;
    }

    @PostMapping("/identities/resolve")
    @PermitAll
    public IdentityView resolveIdentity(@RequestBody IdentityRequest request) {
        return food.resolveIdentity(request);
    }

    @GetMapping("/identities/{subject}")
    @PermitAll
    public IdentityView identity(@PathVariable String subject) {
        return food.identityDetails(subject);
    }

    @PostMapping("/identities/{subject}/sessions/revoke")
    @PermitAll
    public RevokeView revokeSessions(@PathVariable String subject) {
        return sessions.revoke(subject);
    }

    @DeleteMapping("/identities/{subject}")
    @PermitAll
    public void detachIdentity(@PathVariable String subject) {
        food.detachIdentity(subject);
    }

    @GetMapping("/stores")
    @PermitAll
    public List<StoreView> stores(
            @RequestParam double longitude,
            @RequestParam double latitude,
            @RequestParam(defaultValue = "TAKEOUT") String fulfillmentType,
            @RequestParam(defaultValue = "10") double radiusKm) {
        return food.nearbyStores(longitude, latitude, fulfillmentType, radiusKm);
    }

    @GetMapping("/stores/{shopId}/menu")
    @PermitAll
    public List<ProductView> menu(@PathVariable long shopId) {
        return food.menu(shopId);
    }

    @GetMapping("/addresses")
    @PermitAll
    public List<AddressView> addresses(@RequestParam String subject) {
        return food.addresses(subject);
    }

    @PostMapping("/checkout-quotes")
    @PermitAll
    public QuoteView quote(@RequestBody QuoteRequest request) {
        return food.createQuote(request);
    }

    @PostMapping("/orders")
    @PermitAll
    public OrderView createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        return food.createOrder(request, idempotencyKey);
    }

    @GetMapping("/orders")
    @PermitAll
    public List<OrderView> orders(
            @RequestParam String subject,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return food.listOrders(subject, page, size);
    }

    @GetMapping("/orders/{orderRefId}")
    @PermitAll
    public OrderView order(@PathVariable String orderRefId, @RequestParam String subject) {
        return food.getOrder(subject, orderRefId);
    }

    @PostMapping("/orders/{orderNo}/payment-results")
    @PermitAll
    public EventResult paymentResult(
            @PathVariable String orderNo, @RequestBody PaymentResultRequest request) {
        return food.paymentResult(orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/payment-closed")
    @PermitAll
    public EventResult paymentClosed(
            @PathVariable String orderNo, @RequestBody CloseRequest request) {
        return food.paymentClosed(orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/cancellation-requests")
    @PermitAll
    public EventResult cancellation(
            @PathVariable String orderNo, @RequestBody CancellationRequest request) {
        return food.requestCancellation(orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/refund-results")
    @PermitAll
    public EventResult refundResult(
            @PathVariable String orderNo, @RequestBody RefundResultRequest request) {
        return food.refundResult(orderNo, request);
    }
}
