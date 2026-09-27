package com.minipay.consumerbff.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MobileMaskingTest {

    @Test
    void keepsOnlyTheOperatorPrefixAndLastFourDigits() {
        assertThat(MobileMasking.mask("13800138000")).isEqualTo("138****8000");
    }

    @Test
    void neverReturnsTheRawMobileForShortOrOddInput() {
        assertThat(MobileMasking.mask("138001")).isEqualTo("***");
        assertThat(MobileMasking.mask(null)).isEmpty();
        assertThat(MobileMasking.mask("13800138000")).doesNotContain("0013800");
    }

    @Test
    void trimsWhitespaceBeforeMasking() {
        assertThat(MobileMasking.mask(" 13912345678 ")).isEqualTo("139****5678");
    }
}
