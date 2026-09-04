package com.minipay.identity.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ConsoleSmsSenderTest {
    private final ConsoleSmsSender sender = new ConsoleSmsSender();

    @Test
    void printsCodeForLocalIntegrationButMasksFullMobile(CapturedOutput output) {
        sender.sendLoginCode("13800138000", "482915");

        assertThat(output)
                .contains("[LOCAL-ONLY][SENSITIVE]")
                .contains("138****8000")
                .contains("482915")
                .doesNotContain("13800138000");
    }

    @Test
    void doesNotEchoMalformedMobile() {
        assertThat(ConsoleSmsSender.mask("123")).isEqualTo("****");
        assertThat(ConsoleSmsSender.mask(null)).isEqualTo("****");
    }
}
