package com.minipay.agent.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.agent.application.port.VoiceObjectStorage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VoiceObjectStorageConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AliyunVoiceObjectStorage.class,
                    DisabledVoiceObjectStorage.class);

    @Test
    void selectsAliyunStorageWhenProviderComesFromEnvironment() {
        context.withPropertyValues(
                        "minipay.agent.object-storage.provider=aliyun",
                        "minipay.agent.object-storage.endpoint=https://oss-cn-beijing.aliyuncs.com",
                        "minipay.agent.object-storage.region=cn-beijing",
                        "minipay.agent.object-storage.bucket=test-private-bucket",
                        "minipay.agent.object-storage.access-key-id=test-key-id",
                        "minipay.agent.object-storage.access-key-secret=test-key-secret")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(VoiceObjectStorage.class);
                    assertThat(result.getBean(VoiceObjectStorage.class))
                            .isInstanceOf(AliyunVoiceObjectStorage.class);
                });
    }

    @Test
    void keepsDisabledFallbackWhenProviderIsNotConfigured() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(VoiceObjectStorage.class);
            assertThat(result.getBean(VoiceObjectStorage.class))
                    .isInstanceOf(DisabledVoiceObjectStorage.class);
        });
    }

    @Test
    void applicationConfigImportsEnvFromRootAndModuleWorkingDirectories() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("optional:file:.env[.properties]");
            assertThat(yaml).contains("optional:file:../../.env[.properties]");
            assertThat(yaml).contains("${OBJECT_STORAGE_PROVIDER:disabled}");
        }
    }
}
