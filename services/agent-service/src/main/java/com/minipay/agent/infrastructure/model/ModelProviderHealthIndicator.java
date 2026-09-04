package com.minipay.agent.infrastructure.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component("modelProvider")
@ConditionalOnProperty(prefix = "minipay.agent.model", name = "enabled", havingValue = "true")
public final class ModelProviderHealthIndicator implements HealthIndicator {
    private final WebClient client;
    private final String modelName;
    private final Duration cacheDuration;
    private volatile CachedHealth cached;

    public ModelProviderHealthIndicator(
            WebClient.Builder builder,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String modelName,
            @Value("${minipay.agent.model.health-cache:60s}") Duration cacheDuration) {
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        this.modelName = modelName;
        this.cacheDuration = cacheDuration;
    }

    @Override
    public Health health() {
        CachedHealth current = cached;
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.checkedAt().plus(cacheDuration))) return current.health();
        Health checked;
        try {
            ModelList response = client.get().uri("/models").retrieve()
                    .bodyToMono(ModelList.class).block(Duration.ofSeconds(5));
            boolean available = response != null && response.data() != null
                    && response.data().stream().anyMatch(item -> modelName.equals(item.id()));
            checked = available
                    ? Health.up().withDetail("provider", "openai-compatible").withDetail("model", modelName).build()
                    : Health.down().withDetail("reason", "configured-model-unavailable").build();
        } catch (RuntimeException exception) {
            checked = Health.down().withDetail("reason", "provider-unreachable").build();
        }
        cached = new CachedHealth(now, checked);
        return checked;
    }

    private record ModelList(List<ModelItem> data) {}
    private record ModelItem(String id) {}
    private record CachedHealth(Instant checkedAt, Health health) {}
}
