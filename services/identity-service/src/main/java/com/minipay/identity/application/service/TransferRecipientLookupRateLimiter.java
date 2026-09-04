package com.minipay.identity.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TransferRecipientLookupRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final byte[] pepper;
    private final int perUser;
    private final int perIp;

    public TransferRecipientLookupRateLimiter(
            StringRedisTemplate redis,
            @Value("${minipay.identity.captcha-pepper}") String pepper,
            @Value("${minipay.identity.rate-limit.recipient-lookup-per-user:20}") int perUser,
            @Value("${minipay.identity.rate-limit.recipient-lookup-per-ip:100}") int perIp) {
        this.redis = redis;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.perUser = perUser;
        this.perIp = perIp;
    }

    public void check(UUID userId, String clientAddress) {
        try {
            incrementOrReject("user", userId.toString(), perUser);
            incrementOrReject("ip", clientAddress == null ? "" : clientAddress, perIp);
        } catch (TransferRecipientLookupException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TransferRecipientLookupException("RECIPIENT_LOOKUP_UNAVAILABLE");
        }
    }

    private void incrementOrReject(String dimension, String value, int limit) {
        String key = "minipay:recipient-lookup:rate:" + dimension + ":" + digest(value);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > limit) {
            throw new TransferRecipientLookupException("RECIPIENT_LOOKUP_RATE_LIMITED");
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create recipient lookup rate key", exception);
        }
    }
}
