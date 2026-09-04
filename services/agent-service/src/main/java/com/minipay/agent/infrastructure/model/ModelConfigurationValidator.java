package com.minipay.agent.infrastructure.model;

import java.net.URI;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "minipay.agent.model", name = "enabled", havingValue = "true")
public final class ModelConfigurationValidator implements InitializingBean {
    private final String chatMode;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;

    public ModelConfigurationValidator(
            @Value("${spring.ai.model.chat:none}") String chatMode,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:}") String modelName,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.chatMode = chatMode;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
    }

    @Override
    public void afterPropertiesSet() {
        if (!"openai".equalsIgnoreCase(chatMode)) {
            throw new IllegalStateException("Enabled agent model requires MODEL_CHAT_MODE=openai");
        }
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MODEL_BASE_URL must be a valid HTTPS URL", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalStateException("MODEL_BASE_URL must be a valid HTTPS URL");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("MODEL_NAME must not be blank");
        }
        if (apiKey == null || apiKey.isBlank() || "disabled-local-placeholder".equals(apiKey)) {
            throw new IllegalStateException("MODEL_API_KEY must be supplied through a Secret");
        }
    }
}
