package com.minipay.identity.infrastructure.realname;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class RealNameConfigurationTest {
    @Test
    void demoProfileProvidesAnExplicitSandboxProviderAndHmacKey() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        addFirst(environment, loader.load(
                "application", new ClassPathResource("application.yml")));
        addFirst(environment, loader.load(
                "application-demo-auth", new ClassPathResource("application-demo-auth.yml")));

        assertThat(environment.getRequiredProperty(
                "minipay.identity.real-name.provider")).isEqualTo("sandbox");
        assertThat(environment.getRequiredProperty(
                "minipay.identity.real-name.hmac-key")).hasSizeGreaterThanOrEqualTo(32);
    }

    private void addFirst(
            StandardEnvironment environment, List<PropertySource<?>> sources) {
        for (PropertySource<?> source : sources.reversed()) {
            environment.getPropertySources().addFirst(source);
        }
    }
}
