package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.MemoryRepository;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryContextServiceTest {
    private static final UUID USER_ID = UUID.fromString("0198f300-0000-7000-8000-000000000001");

    @Test
    void returnsOnlyRelevantMemories() {
        MemoryRepository repository = mock(MemoryRepository.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(
                new MemorySetting(USER_ID, true, false, false, false, false, false, 1, now, now)));
        when(repository.listItems(USER_ID, null, 100)).thenReturn(List.of(
                item("我喜欢清淡的晚餐", now),
                item("周末喜欢看电影", now.minusSeconds(60))));

        List<String> result = new MemoryContextService(repository)
                .relevantMemories(USER_ID, "推荐一份清淡晚餐");

        assertThat(result).containsExactly("我喜欢清淡的晚餐");
    }

    @Test
    void returnsAuthorizedStructuredAllergenForFoodQuestion() {
        MemoryRepository repository = mock(MemoryRepository.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(repository.findSetting(USER_ID)).thenReturn(Optional.of(
                new MemorySetting(USER_ID, true, false, true, false, false, false, 1, now, now)));
        MemoryItem allergen = new MemoryItem(UUID.randomUUID(), USER_ID,
                MemoryType.ALLERGEN_AVOIDANCE, "不能吃坚果", null, null, "ACTIVE",
                UUID.randomUUID(), "CHAT_EXPLICIT", null, 0, now, now);
        when(repository.listItems(USER_ID, null, 100)).thenReturn(List.of(allergen));

        List<String> result = new MemoryContextService(repository)
                .relevantMemories(USER_ID, "我不能吃什么");

        assertThat(result).containsExactly("不能吃坚果");
    }

    private static MemoryItem item(String value, Instant time) {
        return new MemoryItem(UUID.randomUUID(), USER_ID, MemoryType.CUSTOM, value,
                null, null, "ACTIVE", null, "PROFILE_MANUAL", null, 0, time, time);
    }
}
