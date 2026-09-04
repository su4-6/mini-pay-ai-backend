package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ConsumerSmsChallengeService;
import com.minipay.identity.application.service.ConsumerSmsChallengeService.ConsumerSmsChallenge;
import com.minipay.identity.application.service.ConsumerSmsChallengeService.VerifiedMobile;
import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.MerchantLoginPasswordService;
import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository.ConsumerAccountDisabledException;
import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import com.minipay.identity.infrastructure.security.ConsumerAuthorizationCodeService;
import com.minipay.identity.infrastructure.security.ConsumerAuthorizationCodeService.IssuedAuthorizationCode;
import com.minipay.identity.infrastructure.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/consumer")
public class ConsumerAuthController {
    private final ConsumerSmsChallengeService challenges;
    private final PhoneNumberService phoneNumbers;
    private final ConsumerAccountRepository accounts;
    private final ConsumerAuthorizationCodeService authorizationCodes;
    private final LoginAuditRepository audits;
    private final MerchantLoginPasswordService merchantPasswords;

    public ConsumerAuthController(
            ConsumerSmsChallengeService challenges,
            PhoneNumberService phoneNumbers,
            ConsumerAccountRepository accounts,
            ConsumerAuthorizationCodeService authorizationCodes,
            LoginAuditRepository audits,
            MerchantLoginPasswordService merchantPasswords) {
        this.challenges = challenges;
        this.phoneNumbers = phoneNumbers;
        this.accounts = accounts;
        this.authorizationCodes = authorizationCodes;
        this.audits = audits;
        this.merchantPasswords = merchantPasswords;
    }

    @PostMapping("/code/send")
    public ResponseEntity<ConsumerSmsChallenge> send(
            @Valid @RequestBody SendCodeRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(challenges.create(body.mobile(), request.getRemoteAddr()));
    }

    @PostMapping("/code/verify")
    public AuthorizationCodeResponse verify(
            @Valid @RequestBody VerifyCodeRequest body,
            HttpServletRequest request) {
        String auditIdentifier = body.challengeId();
        try {
            VerifiedMobile verified = challenges.consume(body.challengeId(), body.code());
            auditIdentifier = verified.mobile();
            ConsumerPrincipal consumer = accounts.findOrCreate(
                    phoneNumbers.hash(verified.mobile()),
                    RequestIdFilter.get(request));
            accounts.recordVerifiedPhone(
                    consumer.userId(), verified.mobile(), phoneNumbers.mask(verified.mobile()));
            IssuedAuthorizationCode code = authorizationCodes.issue(
                    consumer,
                    body.clientId(),
                    body.redirectUri(),
                    body.codeChallenge(),
                    body.codeChallengeMethod(),
                    body.deviceId());
            audits.appendLogin(
                    consumer.userId(),
                    auditIdentifier,
                    "CONSUMER_SMS",
                    "SUCCESS",
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent"),
                    RequestIdFilter.get(request));
            return new AuthorizationCodeResponse(
                    code.authorizationCode(),
                    code.expiresAt(),
                    consumer.userId(),
                    consumer.payPasswordSet(),
                    !consumer.onboardingCompleted(),
                    consumer.realNameStatus(),
                    consumer.realNameVerified(),
                    verified.mobile(),
                    merchantPasswords.configured(consumer.userId()));
        } catch (ConsumerAccountDisabledException exception) {
            auditFailure(auditIdentifier, "DISABLED", request);
            throw new LoginRejectedException("ACCOUNT_DISABLED");
        } catch (LoginRejectedException exception) {
            auditFailure(auditIdentifier, auditResult(exception.code()), request);
            throw exception;
        }
    }

    private void auditFailure(String identifier, String result, HttpServletRequest request) {
        audits.appendLogin(
                null,
                identifier,
                "CONSUMER_SMS",
                result,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                RequestIdFilter.get(request));
    }

    private String auditResult(String code) {
        return switch (code) {
            case "SMS_LOCKED", "AUTH_RATE_LIMITED", "SMS_RESEND_TOO_SOON" -> "RATE_LIMITED";
            case "ACCOUNT_DISABLED" -> "DISABLED";
            case "PKCE_INVALID", "OAUTH_CLIENT_INVALID" -> "CLIENT_REJECTED";
            default -> "SMS_FAILED";
        };
    }

    public record SendCodeRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @NotBlank @Pattern(regexp = "^LOGIN$") String purpose) {
    }

    public record VerifyCodeRequest(
            @NotBlank String challengeId,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code,
            @NotBlank String clientId,
            @NotBlank String redirectUri,
            @NotBlank @Size(min = 43, max = 128) String codeChallenge,
            @NotBlank @Pattern(regexp = "^S256$") String codeChallengeMethod,
            @NotBlank @Size(max = 128) String deviceId) {
    }

    public record AuthorizationCodeResponse(
            String authorizationCode,
            Instant expiresAt,
            java.util.UUID userId,
            boolean payPasswordSet,
            boolean onboardingRequired,
            String realNameStatus,
            boolean realNameVerified,
            String phone,
            boolean merchantPasswordConfigured) {
    }
}
