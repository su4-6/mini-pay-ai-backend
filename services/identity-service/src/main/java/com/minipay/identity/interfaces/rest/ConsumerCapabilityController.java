package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.PaymentPasswordService;
import com.minipay.identity.application.service.RealNameVerificationService;
import com.minipay.identity.application.service.RealNameVerificationService.VerificationView;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
public class ConsumerCapabilityController {
    private final RealNameVerificationService realNames;
    private final PaymentPasswordService passwords;
    private final ConsumerAccountRepository accounts;

    public ConsumerCapabilityController(
            RealNameVerificationService realNames,
            PaymentPasswordService passwords,
            ConsumerAccountRepository accounts) {
        this.realNames = realNames;
        this.passwords = passwords;
        this.accounts = accounts;
    }

    @PostMapping("/api/v1/real-name-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationView verify(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam @NotBlank String legalName,
            @RequestParam @NotBlank String idNumber,
            @RequestParam MultipartFile faceImage) throws IOException {
        return realNames.verify(userId(jwt), idempotencyKey, legalName, idNumber, faceImage.getBytes());
    }

    @GetMapping("/api/v1/real-name-verifications/{verificationId}")
    public VerificationView get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID verificationId) {
        return realNames.get(userId(jwt), verificationId);
    }

    @PutMapping("/api/v1/users/me/payment-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPaymentPassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SetPaymentPasswordRequest request) {
        passwords.setInitial(userId(jwt), request.paymentPassword());
    }

    @GetMapping("/api/v1/users/me/capabilities")
    public CapabilityResponse capabilities(@AuthenticationPrincipal Jwt jwt) {
        var principal = accounts.findActive(userId(jwt)).orElseThrow();
        return new CapabilityResponse(
                principal.onboardingCompleted(), principal.realNameStatus(),
                "VERIFIED".equals(principal.realNameStatus()), principal.payPasswordSet());
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record CapabilityResponse(
            boolean onboardingCompleted, String realNameStatus,
            boolean realNameVerified, boolean payPasswordSet) {
    }

    public record SetPaymentPasswordRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^\\d{6}$")
            String paymentPassword) {
    }
}
