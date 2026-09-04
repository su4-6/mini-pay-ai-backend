package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestSupportTest {
    @Test
    void scopesAndBoundsIdempotencyKeysWithoutPersistingTheirPlaintext() {
        UUID userId = UUID.fromString("0197f000-0000-7000-8000-000000000001");
        String supplied = "client-request-0001";

        String stored = RequestSupport.scopedIdempotencyKey(userId, supplied);

        assertThat(stored)
                .startsWith(userId + ":")
                .hasSize(101)
                .doesNotContain(supplied);
    }

    @Test
    void rejectsShortIdempotencyKeys() {
        assertThatThrownBy(() ->
                RequestSupport.scopedIdempotencyKey(UUID.randomUUID(), "short"))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }
}
