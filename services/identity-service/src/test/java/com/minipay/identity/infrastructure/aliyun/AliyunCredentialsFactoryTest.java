package com.minipay.identity.infrastructure.aliyun;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AliyunCredentialsFactoryTest {
    @Test
    void rejectsPartialStaticCredentials() {
        assertThatThrownBy(() -> AliyunCredentialsFactory.create("", "access-key-id", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }
}
