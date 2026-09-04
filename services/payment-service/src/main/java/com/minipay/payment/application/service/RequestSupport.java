package com.minipay.payment.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class RequestSupport {
    private RequestSupport() {
    }

    static String scopedIdempotencyKey(UUID userId, String key) {
        if (key == null || key.length() < 16 || key.length() > 128) {
            throw new PaymentProblemException(
                    "INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
        }
        return userId + ":" + hashText(key);
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String businessNo(String prefix, UUID id) {
        return prefix + id.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    static String hashText(String value) {
        return HexFormat.of().formatHex(hash(value));
    }
}
