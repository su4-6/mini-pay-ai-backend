package com.minipay.identity.application.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(Clock.systemUTC().millis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    static UUID generate(long unixEpochMillis, long highRandomness, long lowRandomness) {
        long timestamp = unixEpochMillis & 0x0000FFFFFFFFFFFFL;
        long mostSignificantBits = (timestamp << 16)
                | 0x7000L
                | (highRandomness & 0x0FFFL);
        long leastSignificantBits = (lowRandomness & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
