package com.minipay.agent.infrastructure.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.agent.application.service.AgentRunApplicationService;
import com.minipay.agent.application.service.AiConversationApplicationService;
import com.minipay.agent.application.service.MemoryApplicationService;
import com.minipay.agent.domain.model.ai.MemoryType;
import com.minipay.agent.domain.model.ai.AiConversation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.ai.model.chat=none",
        "minipay.agent.model.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers(disabledWithoutDocker = true)
class AiAgentPersistenceIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("minipay_agent")
            .withUsername("minipay")
            .withPassword("minipay");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired AiConversationApplicationService conversations;
    @Autowired AgentRunApplicationService runs;
    @Autowired MemoryApplicationService memory;
    @MockitoBean RedisMessageListenerContainer realtimeRedisListener;

    @Test
    void migratesAndPersistsConversationRunMessageAndReplayableEvent() {
        UUID userId = UUID.fromString("0198f100-0000-7000-8000-000000000001");
        AiConversation conversation = conversations.create(userId, "钱包助手");

        AgentRunApplicationService.CreateRunResult created = runs.createRun(
                userId,
                conversation.id(),
                UUID.fromString("0198f100-0000-7000-8000-000000000002"),
                "integration-request-0001",
                "查询我的余额",
                conversation.version());

        assertThat(runs.getRun(userId, created.run().id()).status().name()).isEqualTo("RECEIVED");
        assertThat(conversations.listMessages(userId, conversation.id(), null, 50))
                .singleElement()
                .satisfies(message -> assertThat(message.contentText()).isEqualTo("查询我的余额"));
        assertThat(runs.listEvents(userId, created.run().id(), 0, 50))
                .singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("run.accepted"));
    }

    @Test
    void persistsManualMemoryWithoutConsentMessageId() {
        UUID userId = UUID.fromString("0198f100-0000-7000-8000-000000000010");
        memory.updateSetting(userId, true, false, false, false, false, false, 0);

        var created = memory.create(userId, MemoryType.CUSTOM, "我喜欢清淡晚餐",
                null, null, null, "integration-memory-0001");

        assertThat(created.consentMessageId()).isNull();
        assertThat(created.consentSource()).isEqualTo("PROFILE_MANUAL");
        assertThat(memory.list(userId, MemoryType.CUSTOM, 10))
                .extracting(item -> item.displayValue())
                .containsExactly("我喜欢清淡晚餐");
    }
}
