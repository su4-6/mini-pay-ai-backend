package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberServiceTest {
    private final PhoneNumberService phoneNumbers = new PhoneNumberService("unit-test-phone-pepper");

    @Test
    void normalizesWhitespaceAndMasksMainlandMobile() {
        String normalized = phoneNumbers.normalize(" 138 0013 8000 ");

        assertThat(normalized).isEqualTo("13800138000");
        assertThat(phoneNumbers.mask(normalized)).isEqualTo("138****8000");
    }

    @Test
    void rejectsInvalidMobileWithoutEchoingIt() {
        assertThatThrownBy(() -> phoneNumbers.normalize("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("12345");
    }

    @Test
    void createsStablePepperedHashInsteadOfPersistingThePhone() {
        byte[] first = phoneNumbers.hash("13800138000");
        byte[] second = phoneNumbers.hash("13800138000");

        assertThat(first).containsExactly(second);
        assertThat(first).hasSize(32);
        assertThat(new String(first)).doesNotContain("13800138000");
    }
}
