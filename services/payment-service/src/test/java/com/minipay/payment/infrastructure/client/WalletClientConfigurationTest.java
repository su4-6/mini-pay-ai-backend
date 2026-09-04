package com.minipay.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class WalletClientConfigurationTest {
    private static final String PROPERTY = "minipay.clients.wallet.client-secret";

    @Test
    void usesIdentityRegisteredDemoSecretByDefault() throws IOException {
        assertThat(resolve(Map.of()))
                .isEqualTo("minipay-payment-wallet-demo-secret");
    }

    @Test
    void supportsSharedIdentityRegistrationOverride() throws IOException {
        assertThat(resolve(Map.of(
                "PAYMENT_TO_WALLET_CLIENT_SECRET", "shared-wallet-secret")))
                .isEqualTo("shared-wallet-secret");
    }

    @Test
    void keepsLegacyPaymentServiceOverrideAsHighestPriority() throws IOException {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("PAYMENT_TO_WALLET_CLIENT_SECRET", "shared-wallet-secret");
        overrides.put("PAYMENT_WALLET_CLIENT_SECRET", "legacy-wallet-secret");

        assertThat(resolve(overrides)).isEqualTo("legacy-wallet-secret");
    }

    private String resolve(Map<String, Object> overrides) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("testOverrides", overrides));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load(
                "paymentApplication",
                new ClassPathResource("application.yml"));
        yaml.forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getRequiredProperty(PROPERTY);
    }
}
