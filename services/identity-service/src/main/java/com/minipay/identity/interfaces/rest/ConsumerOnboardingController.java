package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ConsumerOnboardingService;
import com.minipay.identity.application.service.ConsumerOnboardingService.CompletionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/v1/users/me")
public class ConsumerOnboardingController {
    private final ConsumerOnboardingService onboarding;

    public ConsumerOnboardingController(ConsumerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PutMapping("/onboarding")
    public ResponseEntity<OnboardingResponse> complete(
            JwtAuthenticationToken authentication,
            @RequestHeader("Idempotency-Key")
            @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody OnboardingRequest request) {
        UUID userId = UUID.fromString(authentication.getToken().getClaimAsString("user_id"));
        CompletionResult result = onboarding.complete(
                userId,
                idempotencyKey,
                request.nickname(),
                request.avatarUploadId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new OnboardingResponse(
                        result.profile().userId(),
                        result.profile().nickname(),
                        result.profile().payPasswordSet(),
                        result.profile().onboardingCompleted()));
    }

    public record OnboardingRequest(
            @NotBlank @Size(min = 2, max = 20) String nickname,
            UUID avatarUploadId) {
    }

    public record OnboardingResponse(
            UUID userId,
            String nickname,
            boolean payPasswordSet,
            boolean onboardingCompleted) {
    }
}
