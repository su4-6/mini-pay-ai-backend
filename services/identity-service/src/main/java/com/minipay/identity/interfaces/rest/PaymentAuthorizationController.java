package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.PaymentAuthorizationRejectedException;
import com.minipay.identity.application.service.PaymentAuthorizationService;
import com.minipay.identity.application.service.PaymentAuthorizationService.ConsumedAuthorization;
import com.minipay.identity.application.service.PaymentAuthorizationService.IssuedAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class PaymentAuthorizationController {
    private final PaymentAuthorizationService authorizations;

    public PaymentAuthorizationController(PaymentAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @PostMapping("/api/v1/payment-authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedAuthorization issue(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody IssueRequest request) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new PaymentAuthorizationRejectedException(
                    "PAYMENT_AUTHORIZATION_INVALID");
        }
        String tokenDeviceId = jwt.getClaimAsString("device_id");
        if (tokenDeviceId == null || !tokenDeviceId.equals(request.deviceId())) {
            throw new PaymentAuthorizationRejectedException("PAYMENT_AUTHORIZATION_INVALID");
        }
        return authorizations.issue(
                userId,
                idempotencyKey,
                request.subjectType(),
                request.subjectId(),
                request.amountCent(),
                request.deviceId(),
                request.payPassword());
    }

    @PostMapping("/internal/v1/payment-authorizations/verify-and-consume")
    public ConsumedAuthorization verifyAndConsume(
            @Valid @RequestBody VerifyRequest request) {
        return authorizations.verifyAndConsume(
                request.paymentAuthToken(),
                request.userId(),
                request.subjectType(),
                request.subjectId(),
                request.amountCent(),
                request.deviceId());
    }

    public record IssueRequest(
            @NotBlank String subjectType,
            @NotNull UUID subjectId,
            @Min(0) @Max(1_000_000) long amountCent,
            @NotBlank @Size(max = 128) String deviceId,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String payPassword) {
    }

    public record VerifyRequest(
            @NotBlank String paymentAuthToken,
            @NotNull UUID userId,
            @NotBlank String subjectType,
            @NotNull UUID subjectId,
            @Min(0) @Max(1_000_000) long amountCent,
            @NotBlank @Size(max = 128) String deviceId) {
    }
}
