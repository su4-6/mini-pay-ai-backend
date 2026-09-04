package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {
    @Test
    void encodesUnixMillisecondsAndRfcVariant() {
        long millis = 1_753_843_200_123L;
        UUID uuid = UuidV7.generate(millis, 0x123L, 0x456L);

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid.getMostSignificantBits() >>> 16)
                .isEqualTo(millis & 0x0000FFFFFFFFFFFFL);
    }
}
