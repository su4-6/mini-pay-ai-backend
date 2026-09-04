package com.minipay.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SystemAdminReasonEncodingTest {
    @Test
    void decodesBrowserSafeChineseAuditReason() {
        assertThat(SystemAdminController.decodeReason(
                "%E5%88%9B%E5%BB%BA%E5%90%8E%E5%8F%B0%E8%B4%A6%E5%8F%B7"))
                .isEqualTo("创建后台账号");
    }

    @Test
    void keepsPlainAsciiClientsCompatible() {
        assertThat(SystemAdminController.decodeReason("security review"))
                .isEqualTo("security review");
    }

    @Test
    void rejectsMalformedOrShortReasons() {
        assertThatThrownBy(() -> SystemAdminController.decodeReason("%E5%ZZ"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_REASON_ENCODING");
        assertThatThrownBy(() -> SystemAdminController.decodeReason("ok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_REASON");
    }
}
