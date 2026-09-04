package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.MerchantService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class MerchantInternalController {
    private final MerchantService merchants;

    public MerchantInternalController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @GetMapping("/scan-resolutions/{resolutionId}")
    public MerchantService.InternalResolutionView consumeResolution(
            @PathVariable UUID resolutionId,
            @RequestParam String merchantId,
            @RequestParam String appId) {
        return merchants.consumeResolution(resolutionId, merchantId, appId);
    }

    @GetMapping("/merchants/by-owner/{ownerUserId}/active")
    public ActiveMerchantResponse activeByOwner(@PathVariable UUID ownerUserId) {
        MerchantService.ActiveMerchantView outcome = merchants.activeByOwner(ownerUserId);
        return new ActiveMerchantResponse(outcome.active(), outcome.merchant());
    }

    public record ActiveMerchantResponse(boolean active, MerchantService.MerchantView merchant) { }
}
