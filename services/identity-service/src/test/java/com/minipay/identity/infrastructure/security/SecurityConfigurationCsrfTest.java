package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SecurityConfigurationCsrfTest {
    @Test
    void exemptsOnlyTheNativeTransferRecipientWriteFromCsrf() {
        MockHttpServletRequest post = new MockHttpServletRequest(
                "POST", "/api/v1/transfer-recipients/resolve");
        MockHttpServletRequest get = new MockHttpServletRequest(
                "GET", "/api/v1/transfer-recipients/resolve");

        assertThat(SecurityConfiguration.transferRecipientCsrfExemption().matches(post)).isTrue();
        assertThat(SecurityConfiguration.transferRecipientCsrfExemption().matches(get)).isFalse();
    }
}
