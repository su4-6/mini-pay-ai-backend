package com.minipay.identity.application.service;

import com.minipay.identity.application.port.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ConsumerSmsChallengeService {
    private static final int MAX_ATTEMPTS = 5;
    private static final String CHALLENGE_PREFIX = "minipay:auth:consumer:challenge:";
    private static final String LATEST_PREFIX = "minipay:auth:consumer:latest:";
    private static final String RESEND_PREFIX = "minipay:auth:consumer:resend:";
    private static final String LOCK_PREFIX = "minipay:auth:consumer:lock:";
    private static final DefaultRedisScript<String> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 'EXPIRED'
            end
            local phoneHash = redis.call('HGET', KEYS[1], 'phoneHash')
            local lockKey = ARGV[4] .. phoneHash
            if redis.call('EXISTS', lockKey) == 1 then
              return 'LOCKED'
            end
            if redis.call('HGET', KEYS[1], 'codeDigest') ~= ARGV[1] then
              local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
              if attempts >= tonumber(ARGV[2]) then
                redis.call('SET', lockKey, '1', 'EX', tonumber(ARGV[3]))
                redis.call('DEL', KEYS[1])
                redis.call('DEL', ARGV[5] .. phoneHash)
                return 'LOCKED'
              end
              return 'INVALID'
            end
            local mobile = redis.call('HGET', KEYS[1], 'mobile')
            redis.call('DEL', KEYS[1])
            redis.call('DEL', ARGV[5] .. phoneHash)
            return 'OK:' .. mobile
            """, String.class);

    private final StringRedisTemplate redis;
    private final PhoneNumberService phoneNumbers;
    private final SmsSender smsSender;
    private final AuthRateLimitService rateLimits;
    private final byte[] pepper;
    private final Duration ttl;
    private final Duration resendAfter;
    private final Duration lockDuration;
    private final SecureRandom random = new SecureRandom();

    public ConsumerSmsChallengeService(
            StringRedisTemplate redis,
            PhoneNumberService phoneNumbers,
            SmsSender smsSender,
            AuthRateLimitService rateLimits,
            @Value("${minipay.identity.captcha-pepper}") String pepper,
            @Value("${minipay.identity.sms.code-ttl}") Duration ttl,
            @Value("${minipay.identity.sms.resend-after}") Duration resendAfter,
            @Value("${minipay.identity.sms.consumer-lock-duration:10m}") Duration lockDuration) {
        this.redis = redis;
        this.phoneNumbers = phoneNumbers;
        this.smsSender = smsSender;
        this.rateLimits = rateLimits;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.resendAfter = resendAfter;
        this.lockDuration = lockDuration;
    }

    public ConsumerSmsChallenge create(String rawMobile, String clientAddress) {
        String mobile;
        try {
            mobile = phoneNumbers.normalize(rawMobile);
        } catch (IllegalArgumentException exception) {
            throw new LoginRejectedException("MOBILE_INVALID");
        }
        String phoneHash = phoneNumbers.hashHex(mobile);
        if (Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + phoneHash))) {
            throw new LoginRejectedException("SMS_LOCKED", remainingSeconds(LOCK_PREFIX + phoneHash));
        }
        rateLimits.checkSmsRequest(mobile, clientAddress);
        Boolean resendAllowed = redis.opsForValue()
                .setIfAbsent(RESEND_PREFIX + phoneHash, "1", resendAfter);
        if (!Boolean.TRUE.equals(resendAllowed)) {
            throw new LoginRejectedException(
                    "SMS_RESEND_TOO_SOON", remainingSeconds(RESEND_PREFIX + phoneHash));
        }

        String code = smsSender.demoCodeForView() == null
                ? String.format("%06d", random.nextInt(1_000_000))
                : smsSender.demoCodeForView();
        String challengeId = UUID.randomUUID().toString();
        try {
            String generated = smsSender.sendAndGetConsumerLoginCode(mobile);
            if (generated != null) {
                // 渠道（如阿里云号码认证）自行生成并下发验证码，用云端返回的码落库
                code = generated;
            } else {
                smsSender.sendConsumerLoginCode(mobile, code);
            }
            String previous = redis.opsForValue().getAndSet(LATEST_PREFIX + phoneHash, challengeId);
            redis.expire(LATEST_PREFIX + phoneHash, ttl);
            if (previous != null && !previous.isBlank()) {
                redis.delete(CHALLENGE_PREFIX + previous);
            }
            redis.opsForHash().putAll(CHALLENGE_PREFIX + challengeId, Map.of(
                    "codeDigest", digest(code),
                    "mobile", mobile,
                    "phoneHash", phoneHash,
                    "attempts", "0"));
            redis.expire(CHALLENGE_PREFIX + challengeId, ttl);

            return new ConsumerSmsChallenge(
                    challengeId,
                    phoneNumbers.mask(mobile),
                    Instant.now().plus(ttl),
                    resendAfter.toSeconds());
        } catch (RuntimeException exception) {
            redis.delete(CHALLENGE_PREFIX + challengeId);
            redis.delete(LATEST_PREFIX + phoneHash);
            redis.delete(RESEND_PREFIX + phoneHash);
            throw exception;
        }
    }

    public VerifiedMobile consume(String challengeId, String code) {
        String result = redis.execute(
                VERIFY_SCRIPT,
                java.util.List.of(CHALLENGE_PREFIX + challengeId),
                digest(code == null ? "" : code.trim()),
                Integer.toString(MAX_ATTEMPTS),
                Long.toString(lockDuration.toSeconds()),
                LOCK_PREFIX,
                LATEST_PREFIX);
        if (result != null && result.startsWith("OK:")) {
            return new VerifiedMobile(result.substring(3));
        }
        if ("LOCKED".equals(result)) {
            throw new LoginRejectedException("SMS_LOCKED", lockDuration.toSeconds());
        }
        if ("INVALID".equals(result)) {
            throw new LoginRejectedException("SMS_INVALID");
        }
        throw new LoginRejectedException("SMS_EXPIRED");
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest consumer SMS code", exception);
        }
    }

    private long remainingSeconds(String key) {
        Long seconds = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return seconds == null || seconds < 0 ? 0 : seconds;
    }

    public record ConsumerSmsChallenge(
            String challengeId,
            String maskedMobile,
            Instant expiresAt,
            long resendAfterSeconds) {
    }

    public record VerifiedMobile(String mobile) {
    }
}
