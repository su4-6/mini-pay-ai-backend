package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.MemoryRepository;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryApplicationServiceTest {
    private static final UUID USER_ID = UUID.fromString("0198f300-0000-7000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("0198f300-0000-7000-8000-000000000002");
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private MemoryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new MemoryApplicationService(repository, new MemoryContentPolicy());
    }

    @Test
    void requiresEnabledCategoryAndOwnedConsentMessage() {
        when(repository.findSetting(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID, MemoryType.FOOD_PREFERENCE,
                "少辣", null, null, MESSAGE_ID, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_MEMORY_CONSENT_REQUIRED"));

        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(enabled(MemoryType.FOOD_PREFERENCE)));
        when(repository.isOwnedUserMessage(USER_ID, MESSAGE_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.create(USER_ID, MemoryType.FOOD_PREFERENCE,
                "少辣", null, null, MESSAGE_ID, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_MEMORY_CONSENT_INVALID"));
    }

    @Test
    void rejectsPhoneAndDetailedAddressInsteadOfPersistingThem() {
        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(allEnabled()));
        when(repository.isOwnedUserMessage(USER_ID, MESSAGE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(USER_ID, MemoryType.CONTACT_ALIAS,
                "妈妈 13800138000", "CONTACT_RELATION", UUID.randomUUID().toString(), MESSAGE_ID,
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_MEMORY_VALUE_REJECTED"));
        assertThatThrownBy(() -> service.create(USER_ID, MemoryType.ADDRESS_ALIAS,
                "幸福路88号", "DELIVERY_ADDRESS", UUID.randomUUID().toString(), MESSAGE_ID,
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_MEMORY_VALUE_REJECTED"));
    }

    @Test
    void savesOnlyStructuredAddressAliasAndReference() {
        UUID addressId = UUID.fromString("0198f300-0000-7000-8000-000000000003");
        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(allEnabled()));
        when(repository.isOwnedUserMessage(USER_ID, MESSAGE_ID)).thenReturn(true);
        when(repository.insertItem(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MemoryItem result = service.create(USER_ID, MemoryType.ADDRESS_ALIAS, "家",
                "delivery_address", addressId.toString(), MESSAGE_ID, UUID.randomUUID().toString());

        assertThat(result.displayValue()).isEqualTo("家");
        assertThat(result.referenceType()).isEqualTo("DELIVERY_ADDRESS");
        assertThat(result.referenceId()).isEqualTo(addressId.toString());
        assertThat(result.consentMessageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    void savesManualCustomMemoryWithoutChatMessageButRejectsCredentials() {
        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(allEnabled()));
        when(repository.findByManualIdempotencyHash(any(), any())).thenReturn(Optional.empty());
        when(repository.insertItem(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MemoryItem result = service.create(USER_ID, MemoryType.CUSTOM, "  我喜欢清淡的晚餐  ",
                null, null, null, "memory-key-0001");

        assertThat(result.displayValue()).isEqualTo("我喜欢清淡的晚餐");
        assertThat(result.consentMessageId()).isNull();
        assertThat(result.consentSource()).isEqualTo("PROFILE_MANUAL");
        assertThatThrownBy(() -> service.create(USER_ID, MemoryType.CUSTOM,
                "token=secret-value", null, null, null, "memory-key-0002"))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_MEMORY_VALUE_REJECTED"));
    }

    private static MemorySetting enabled(MemoryType type) {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        return new MemorySetting(USER_ID, true, type == MemoryType.FOOD_PREFERENCE,
                type == MemoryType.ALLERGEN_AVOIDANCE, type == MemoryType.MEAL_BUDGET,
                type == MemoryType.CONTACT_ALIAS, type == MemoryType.ADDRESS_ALIAS, 1, now, now);
    }

    private static MemorySetting allEnabled() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        return new MemorySetting(USER_ID, true, true, true, true, true, true, 1, now, now);
    }
}
