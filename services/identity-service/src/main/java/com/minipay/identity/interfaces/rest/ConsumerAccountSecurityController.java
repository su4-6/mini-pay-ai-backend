package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ConsumerAccountSecurityService;
import com.minipay.identity.application.service.ConsumerAccountSecurityService.PaymentPasswordVerification;
import com.minipay.identity.application.service.ConsumerAccountSecurityService.VerificationChallenge;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository.AccountSecurityView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/users/me")
public class ConsumerAccountSecurityController {
    private final ConsumerAccountSecurityService service;

    public ConsumerAccountSecurityController(ConsumerAccountSecurityService service) {
        this.service = service;
    }

    @GetMapping("/account-security")
    public AccountSecurityView get(JwtAuthenticationToken auth) {
        return service.get(userId(auth));
    }

    @PostMapping("/phone-change-challenges")
    public ResponseEntity<VerificationChallenge> phoneChallenge(
            JwtAuthenticationToken auth,
            @Valid @RequestBody PhoneRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            HttpServletRequest servlet) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestPhoneChange(
                userId(auth), request.mobile(), servlet.getRemoteAddr(), idempotencyKey));
    }

    @PutMapping("/phone")
    public ResponseEntity<Void> confirmPhone(
            JwtAuthenticationToken auth,
            @Valid @RequestBody CodeRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        service.confirmPhoneChange(userId(auth), request.challengeId(), request.code(), idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/phone-disclosure-challenges")
    public ResponseEntity<VerificationChallenge> phoneDisclosureChallenge(
            JwtAuthenticationToken auth,
            @Valid @RequestBody PhoneRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            HttpServletRequest servlet) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestPhoneDisclosure(
                userId(auth), request.mobile(), servlet.getRemoteAddr(), idempotencyKey));
    }

    @PostMapping("/phone-disclosure-verifications")
    public ResponseEntity<Void> confirmPhoneDisclosure(
            JwtAuthenticationToken auth,
            @Valid @RequestBody CodeRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        service.confirmPhoneDisclosure(
                userId(auth), request.challengeId(), request.code(), idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email-verification-challenges")
    public ResponseEntity<VerificationChallenge> emailChallenge(
            JwtAuthenticationToken auth,
            @Valid @RequestBody EmailRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.requestEmail(userId(auth), request.email(), idempotencyKey));
    }

    @PutMapping("/email")
    public ResponseEntity<Void> confirmEmail(
            JwtAuthenticationToken auth,
            @Valid @RequestBody CodeRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        service.confirmEmail(userId(auth), request.challengeId(), request.code(), idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/email")
    public ResponseEntity<Void> removeEmail(
            JwtAuthenticationToken auth,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        service.removeEmail(userId(auth), idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payment-password-change-challenges")
    public ResponseEntity<VerificationChallenge> paymentPasswordChallenge(
            JwtAuthenticationToken auth,
            @Valid @RequestBody CurrentMobileDeviceRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            HttpServletRequest servlet) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                service.requestPaymentPasswordChallenge(userId(auth), request.mobile(),
                        request.deviceId(), servlet.getRemoteAddr(), idempotencyKey));
    }

    @PostMapping("/payment-password-change-challenges/{challengeId}/verifications")
    public PaymentPasswordVerification verifyPaymentPasswordChallenge(
            JwtAuthenticationToken auth,
            @PathVariable @NotBlank String challengeId,
            @Valid @RequestBody PaymentCodeRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        return service.verifyPaymentPasswordChallenge(userId(auth), challengeId,
                request.code(), request.deviceId(), idempotencyKey);
    }

    @PostMapping("/payment-password-changes")
    public ResponseEntity<Void> changePaymentPassword(
            JwtAuthenticationToken auth,
            @Valid @RequestBody PaymentPasswordChangeRequest request,
            @RequestHeader("X-Request-Id") @NotBlank @Size(max = 128) String requestId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        service.changePaymentPassword(userId(auth), request.verificationToken(),
                request.newPassword(), request.deviceId(), idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(JwtAuthenticationToken auth) {
        String claim = auth.getToken().getClaimAsString("user_id");
        return UUID.fromString(claim == null ? auth.getToken().getSubject() : claim);
    }

    public record PhoneRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile) {
    }

    public record EmailRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record CodeRequest(
            @NotBlank String challengeId,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record CurrentMobileDeviceRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @NotBlank @Size(max = 128) String deviceId) {
    }

    public record PaymentCodeRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code,
            @NotBlank @Size(max = 128) String deviceId) {
    }

    public record PaymentPasswordChangeRequest(
            @NotBlank String verificationToken,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String newPassword,
            @NotBlank @Size(max = 128) String deviceId) {
    }
}
