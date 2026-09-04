package com.minipay.identity.application.service;

import com.minipay.identity.application.port.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SmsChallengeService {
    private final StringRedisTemplate redis;
    private final PhoneNumberService phoneNumbers;
    private final AdminAuthenticationService authentication;
    private final SmsSender smsSender;
    private final CaptchaService captchas;
    private final byte[] pepper;
    private final Duration ttl;
    private final Duration resendAfter;
    private final SecureRandom random = new SecureRandom();

    public SmsChallengeService(
            StringRedisTemplate redis,
            PhoneNumberService phoneNumbers,
            AdminAuthenticationService authentication,
            SmsSender smsSender,
            CaptchaService captchas,
            @Value("${minipay.identity.captcha-pepper}") String pepper,
            @Value("${minipay.identity.sms.code-ttl}") Duration ttl,
            @Value("${minipay.identity.sms.resend-after}") Duration resendAfter) {
        this.redis = redis;
        this.phoneNumbers = phoneNumbers;
        this.authentication = authentication;
        this.smsSender = smsSender;
        this.captchas = captchas;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.resendAfter = resendAfter;
    }

    public SmsChallenge create(String rawPhone, String captchaId, String captchaCode) {
        captchas.consume(captchaId, captchaCode);
        String phone;
        try {
            phone = phoneNumbers.normalize(rawPhone);
        } catch (IllegalArgumentException exception) {
            throw new LoginRejectedException("LOGIN_REJECTED");
        }
        enforceRateLimit(phone);

        boolean eligible = authentication.eligibleSmsAccount(phone).isPresent();
        String code = smsSender.demoCodeForView() == null
                ? String.format("%06d", random.nextInt(1_000_000))
                : smsSender.demoCodeForView();
        String challengeId = UUID.randomUUID().toString();
        try {
            if (eligible) {
                String generated = smsSender.sendAndGetLoginCode(phone);
                if (generated != null) {
                    // 渠道（如阿里云号码认证）自行生成并下发验证码，用云端返回的码落库
                    code = generated;
                } else {
                    smsSender.sendLoginCode(phone, code);
                }
            }
            String value = digest(code) + ":" + phoneNumbers.hashHex(phone) + ":" + eligible;
            redis.opsForValue().set(challengeKey(challengeId), value, ttl);
        } catch (RuntimeException exception) {
            // 发送/落库任一失败都清除残留（可能不存在，删除是幂等安全操作）
            redis.delete(challengeKey(challengeId));
            throw exception;
        }
        return new SmsChallenge(
                challengeId,
                phoneNumbers.mask(phone),
                Instant.now().plus(ttl),
                resendAfter.toSeconds(),
                smsSender.demoCodeForView());
    }

    public void consume(String challengeId, String rawPhone, String code) {
        String stored = challengeId == null ? null : redis.opsForValue().getAndDelete(challengeKey(challengeId));
        String phone;
        try {
            phone = phoneNumbers.normalize(rawPhone);
        } catch (IllegalArgumentException exception) {
            throw new LoginRejectedException("SMS_INVALID");
        }
        if (stored == null) {
            throw new LoginRejectedException("SMS_INVALID");
        }
        String[] parts = stored.split(":", 3);
        boolean valid = parts.length == 3
                && MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.UTF_8),
                digest(code == null ? "" : code.trim()).getBytes(StandardCharsets.UTF_8))
                && MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),
                phoneNumbers.hashHex(phone).getBytes(StandardCharsets.UTF_8))
                && Boolean.parseBoolean(parts[2]);
        if (!valid) {
            throw new LoginRejectedException("SMS_INVALID");
        }
    }

    private void enforceRateLimit(String phone) {
        String key = "minipay:auth:sms-rate:" + phoneNumbers.hashHex(phone);
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1) {
            redis.expire(key, Duration.ofMinutes(10));
        }
        if (value != null && value > 5) {
            throw new LoginRejectedException("SMS_RATE_LIMITED");
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest SMS code", exception);
        }
    }

    private String challengeKey(String id) {
        return "minipay:auth:sms:" + id;
    }

    public record SmsChallenge(
            String challengeId,
            String maskedPhone,
            Instant expiresAt,
            long resendAfterSeconds,
            String demoCode) {
    }
}
