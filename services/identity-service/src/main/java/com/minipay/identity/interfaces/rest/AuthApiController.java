package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.CaptchaService;
import com.minipay.identity.application.service.CaptchaService.CaptchaChallenge;
import com.minipay.identity.application.service.AuthRateLimitService;
import com.minipay.identity.application.service.SmsChallengeService;
import com.minipay.identity.application.service.SmsChallengeService.SmsChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {
    private final CaptchaService captchas;
    private final SmsChallengeService smsChallenges;
    private final AuthRateLimitService rateLimits;

    public AuthApiController(
            CaptchaService captchas,
            SmsChallengeService smsChallenges,
            AuthRateLimitService rateLimits) {
        this.captchas = captchas;
        this.smsChallenges = smsChallenges;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/captchas")
    public CaptchaChallenge createCaptcha(HttpServletRequest request) {
        rateLimits.checkCaptchaRequest(request.getRemoteAddr());
        return captchas.create();
    }

    @GetMapping(value = "/captchas/{captchaId}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> captchaImage(@PathVariable String captchaId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(captchas.image(captchaId));
    }

    @PostMapping("/sms-challenges")
    public SmsChallenge createSmsChallenge(
            @Valid @RequestBody SmsChallengeRequest request,
            HttpServletRequest servletRequest) {
        rateLimits.checkSmsRequest(request.phone(), servletRequest.getRemoteAddr());
        return smsChallenges.create(
                request.phone(),
                request.captchaId(),
                request.captchaCode());
    }

    public record SmsChallengeRequest(
            @NotBlank String phone,
            @NotBlank String captchaId,
            @NotBlank String captchaCode) {
    }
}
