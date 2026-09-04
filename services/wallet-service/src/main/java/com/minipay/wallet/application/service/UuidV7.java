package com.minipay.wallet.application.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = Clock.systemUTC().millis() & 0x0000FFFFFFFFFFFFL;
        long mostSignificantBits = (timestamp << 16)
                | 0x7000L
                | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificantBits = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
