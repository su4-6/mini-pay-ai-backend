package com.minipay.identity.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimitService {
    private final StringRedisTemplate redis;
    private final byte[] pepper;
    private final int captchaPerFiveMinutes;
    private final int loginPerFiveMinutes;

    public AuthRateLimitService(
            StringRedisTemplate redis,
            @Value("${minipay.identity.captcha-pepper}") String pepper,
            @Value("${minipay.identity.rate-limit.captcha-per-five-minutes:30}") int captchaPerFiveMinutes,
            @Value("${minipay.identity.rate-limit.login-per-five-minutes:20}") int loginPerFiveMinutes) {
        this.redis = redis;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.captchaPerFiveMinutes = captchaPerFiveMinutes;
        this.loginPerFiveMinutes = loginPerFiveMinutes;
    }

    public void checkCaptchaRequest(String clientAddress) {
        incrementOrReject("captcha-ip", clientAddress, captchaPerFiveMinutes);
    }

    public void checkLoginAttempt(String phone, String clientAddress) {
        incrementOrReject("login-ip", clientAddress, loginPerFiveMinutes);
        incrementOrReject("login-account", phone, loginPerFiveMinutes);
    }

    public void checkSmsRequest(String phone, String clientAddress) {
        incrementOrReject("sms-ip", clientAddress, loginPerFiveMinutes);
        incrementOrReject("sms-account", phone, loginPerFiveMinutes);
    }

    private void incrementOrReject(String dimension, String value, int limit) {
        String key = "minipay:auth:rate:" + dimension + ":" + digest(value == null ? "" : value);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(5));
        }
        if (count != null && count > limit) {
            throw new LoginRejectedException("AUTH_RATE_LIMITED");
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create rate-limit key", exception);
        }
    }
}
