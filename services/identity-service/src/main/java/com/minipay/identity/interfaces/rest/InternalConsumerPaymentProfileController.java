package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ConsumerProfileService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow service-to-service view used only to render a confirmed personal collection target.
 * Identity remains the owner of profile and real-name data.
 */
@RestController
@RequestMapping("/internal/v1/consumer-payment-profiles")
public class InternalConsumerPaymentProfileController {
    private final ConsumerProfileService profiles;

    public InternalConsumerPaymentProfileController(ConsumerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{userId}")
    public ConsumerPaymentProfile get(@PathVariable UUID userId) {
        ConsumerProfileService.ProfileView profile = profiles.get(userId);
        return new ConsumerPaymentProfile(
                profile.userId(),
                profile.nickname(),
                profile.avatarUrl(),
                profile.avatarUrlExpiresAt(),
                profile.legalNameMasked());
    }

    @GetMapping
    public List<ConsumerPaymentProfile> getBatch(@RequestParam List<UUID> userId) {
        if (userId.isEmpty() || userId.size() > 20) {
            throw new com.minipay.identity.application.service.ProfileRejectedException(
                    "PROFILE_BATCH_SIZE_OUT_OF_RANGE");
        }
        return userId.stream().distinct().map(this::get).toList();
    }

    public record ConsumerPaymentProfile(
            UUID userId,
            String nickname,
            String avatarUrl,
            Instant avatarUrlExpiresAt,
            String legalNameMasked) {
    }
}
