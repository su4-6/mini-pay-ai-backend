package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.application.service.CaptchaService;
import com.minipay.identity.application.service.ConsumerSmsChallengeService;
import com.minipay.identity.application.service.MerchantLoginPasswordService;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import com.minipay.identity.infrastructure.security.ConsumerAuthorizationCodeService;
import com.minipay.identity.infrastructure.security.ConsumerAuthorizationCodeService.IssuedAuthorizationCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Password sign-in is for the merchant portal only; the wallet account remains independent. */
@RestController
@RequestMapping("/api/v1/auth/merchant")
public class MerchantPasswordAuthController {
    private final PhoneNumberService phones;
    private final ConsumerAccountRepository accounts;
    private final MerchantLoginPasswordService credentials;
    private final ConsumerAuthorizationCodeService authorizationCodes;
    private final CaptchaService captchas;
    private final ConsumerSmsChallengeService smsChallenges;

    public MerchantPasswordAuthController(PhoneNumberService phones, ConsumerAccountRepository accounts,
            MerchantLoginPasswordService credentials, ConsumerAuthorizationCodeService authorizationCodes,
            CaptchaService captchas, ConsumerSmsChallengeService smsChallenges) {
        this.phones = phones;
        this.accounts = accounts;
        this.credentials = credentials;
        this.authorizationCodes = authorizationCodes;
        this.captchas = captchas;
        this.smsChallenges = smsChallenges;
    }

    /** Merchant-only route: preserves the consumer login contract while adding the web captcha gate. */
    @PostMapping("/code/send")
    public ConsumerSmsChallengeService.ConsumerSmsChallenge sendCode(
            @Valid @RequestBody MerchantCodeRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        captchas.consume(request.captchaId(), request.captchaCode());
        return smsChallenges.create(request.mobile(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/password/verify")
    public ResponseEntity<AuthorizationCodeResponse> verify(@Valid @RequestBody PasswordLoginRequest request) {
        ConsumerPrincipal user;
        try {
            captchas.consume(request.captchaId(), request.captchaCode());
            user = accounts.findByPhoneHash(phones.hash(phones.normalize(request.mobile())))
                    .orElseThrow(() -> new LoginRejectedException("MERCHANT_LOGIN_REJECTED"));
            credentials.verify(user.userId(), request.password());
        } catch (ConsumerAccountRepository.ConsumerAccountDisabledException exception) {
            throw new LoginRejectedException("ACCOUNT_DISABLED");
        } catch (IllegalArgumentException exception) {
            throw new LoginRejectedException("MERCHANT_LOGIN_REJECTED");
        }
        IssuedAuthorizationCode code = authorizationCodes.issue(user, request.clientId(), request.redirectUri(),
                request.codeChallenge(), request.codeChallengeMethod(), request.deviceId());
        return ResponseEntity.status(HttpStatus.OK).body(new AuthorizationCodeResponse(code.authorizationCode(), code.expiresAt()));
    }

    @PutMapping("/password")
    public MerchantLoginPasswordService.PasswordStatus changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        return credentials.change(subject(jwt), request.currentPassword(), request.newPassword());
    }

    @PutMapping("/password/reset")
    public MerchantLoginPasswordService.PasswordStatus resetPassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResetPasswordRequest request) {
        return credentials.resetAfterSms(subject(jwt), request.newPassword());
    }

    private static java.util.UUID subject(Jwt jwt) {
        if (jwt == null) {
            throw new LoginRejectedException("MERCHANT_REAUTHENTICATION_REQUIRED");
        }
        try {
            return java.util.UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new LoginRejectedException("MERCHANT_REAUTHENTICATION_REQUIRED");
        }
    }

    public record PasswordLoginRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @NotBlank @Size(min = 6, max = 20) String password,
            @NotBlank String captchaId, @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{4}$") String captchaCode,
            @NotBlank String clientId, @NotBlank String redirectUri,
            @NotBlank @Size(min = 43, max = 128) String codeChallenge,
            @NotBlank @Pattern(regexp = "^S256$") String codeChallengeMethod,
            @NotBlank @Size(max = 128) String deviceId) { }
    public record MerchantCodeRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @NotBlank String captchaId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{4}$") String captchaCode) { }
    public record ChangePasswordRequest(
            @Size(max = 20) String currentPassword,
            @NotBlank @Size(min = 12, max = 20) String newPassword) { }
    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12, max = 20) String newPassword) { }
    public record AuthorizationCodeResponse(String authorizationCode, Instant expiresAt) { }
}
