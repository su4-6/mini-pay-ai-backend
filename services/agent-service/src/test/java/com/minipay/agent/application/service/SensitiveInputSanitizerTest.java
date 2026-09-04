package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SensitiveInputSanitizerTest {
    private final SensitiveInputSanitizer sanitizer = new SensitiveInputSanitizer();

    @Test
    void extractsOneExactMobileWithoutKeepingItInModelText() {
        SensitiveInputSanitizer.SanitizedInput result = sanitizer.sanitize("给 13800138000 转 50 元");

        assertThat(result.text()).isEqualTo("给 [MOBILE_EXACT] 转 50 元");
        assertThat(result.exactMobile()).contains("13800138000");
        assertThat(result.text()).doesNotContain("13800138000");
    }

    @Test
    void rejectsCredentialsBeforePersistence() {
        assertThatThrownBy(() -> sanitizer.sanitize("支付密码是 123456"))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("AGENT_SENSITIVE_CREDENTIAL_REJECTED"));
        assertThatThrownBy(() -> sanitizer.sanitize(
                "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature12345"))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("AGENT_SENSITIVE_CREDENTIAL_REJECTED"));
    }

    @Test
    void rejectsMultipleExactMobiles() {
        assertThatThrownBy(() -> sanitizer.sanitize("13800138000 和 13900139000"))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("AGENT_MULTIPLE_MOBILES_UNSUPPORTED"));
    }
}
