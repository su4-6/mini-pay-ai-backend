package com.minipay.agent.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelConfigurationValidatorTest {
    @Test
    void acceptsOpenAiCompatibleHttpsConfiguration() {
        var validator = new ModelConfigurationValidator(
                "openai", "https://api.deepseek.com", "deepseek-v4-flash", "test-secret");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSecretAndInsecureEndpoint() {
        assertThatThrownBy(() -> new ModelConfigurationValidator(
                "openai", "https://api.deepseek.com", "deepseek-v4-flash", "").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODEL_API_KEY");
        assertThatThrownBy(() -> new ModelConfigurationValidator(
                "openai", "http://api.deepseek.com", "deepseek-v4-flash", "test-secret")
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }
}
