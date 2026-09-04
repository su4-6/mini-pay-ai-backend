package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.YshopFoodIntegrationService;
import com.minipay.commerce.application.YshopFoodIntegrationService.HandoffIdentity;
import com.minipay.commerce.application.YshopFoodIntegrationService.ResolvedLocationView;
import com.minipay.commerce.application.YshopPaymentEventService;
import com.minipay.commerce.application.YshopPaymentEventService.RefundSubmission;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class YshopSignedInternalController {
    private final YshopFoodIntegrationService food;
    private final YshopPaymentEventService payments;

    public YshopSignedInternalController(
            YshopFoodIntegrationService food,
            YshopPaymentEventService payments) {
        this.food = food;
        this.payments = payments;
    }

    @PostMapping("/food-handoffs/consume")
    public HandoffIdentity consume(@RequestBody ConsumeHandoffRequest request) {
        return food.consumeHandoff(request.code(), request.deviceProof(), request.origin());
    }

    @PostMapping("/food-location-contexts/{locationContextId}/resolve")
    public ResolvedLocationView resolveLocation(
            @PathVariable UUID locationContextId,
            @RequestBody ResolveLocationRequest request) {
        return food.resolveLocation(locationContextId, request.subject());
    }

    @PostMapping("/yshop/food-orders/{orderRefId}/refunds")
    public RefundSubmission refund(
            @PathVariable String orderRefId,
            @RequestBody RefundRequest request) {
        return payments.submitRefundByExternalOrderNo(
                orderRefId, UUID.fromString(request.refundRequestId()), request.reason());
    }

    public record ConsumeHandoffRequest(String code, String deviceProof, String origin) { }
    public record RefundRequest(String refundRequestId, String reason) { }
    public record ResolveLocationRequest(String subject) { }
}
