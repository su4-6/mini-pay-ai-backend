package com.minipay.commerce.application;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long most = (timestamp << 16) | 0x7000L | RANDOM.nextInt(0x1000);
        long least = RANDOM.nextLong();
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}
