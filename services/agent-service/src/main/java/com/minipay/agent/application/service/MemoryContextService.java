package com.minipay.agent.application.service;

import com.minipay.agent.application.port.MemoryRepository;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryContextService {
    private final MemoryRepository repository;

    public MemoryContextService(MemoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<String> relevantMemories(UUID userId, String message) {
        MemorySetting setting = repository.findSetting(userId)
                .filter(MemorySetting::enabled).orElse(null);
        if (setting == null) return List.of();
        Set<String> queryTokens = tokens(message);
        boolean foodTask = isFoodTask(message);
        if (queryTokens.isEmpty() && !foodTask) return List.of();
        return repository.listItems(userId, null, 100).stream()
                .filter(item -> setting.permits(item.type()))
                .map(item -> new Scored(item, score(
                        queryTokens, tokens(item.displayValue()), message,
                        foodTask && isFoodMemory(item.type()))))
                .filter(value -> value.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(value -> value.item().updatedAt(), Comparator.reverseOrder()))
                .limit(5)
                .map(value -> value.item().displayValue())
                .toList();
    }

    private static int score(
            Set<String> query, Set<String> memory, String rawQuery, boolean foodMemory) {
        int overlap = 0;
        for (String token : memory) if (query.contains(token)) overlap++;
        String normalizedQuery = normalize(rawQuery);
        return overlap * 10 + (memory.stream().anyMatch(token -> token.length() >= 2
                && normalizedQuery.contains(token)) ? 3 : 0) + (foodMemory ? 1 : 0);
    }

    private static boolean isFoodTask(String message) {
        String value = normalize(message);
        return List.of("外卖", "点餐", "吃", "喝", "餐", "菜", "推荐", "食品")
                .stream().anyMatch(value::contains);
    }

    private static boolean isFoodMemory(MemoryType type) {
        return type == MemoryType.FOOD_PREFERENCE
                || type == MemoryType.ALLERGEN_AVOIDANCE
                || type == MemoryType.MEAL_BUDGET;
    }

    private static Set<String> tokens(String value) {
        String normalized = normalize(value);
        Set<String> result = new HashSet<>();
        for (String word : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            if (word.isBlank()) continue;
            if (word.codePoints().allMatch(code -> Character.UnicodeScript.of(code)
                    == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) result.add(word);
                for (int index = 0; index < points.length - 1; index++) {
                    result.add(new String(points, index, 2));
                }
            } else if (word.length() >= 2) {
                result.add(word);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).strip();
    }

    private record Scored(MemoryItem item, int score) {}
}
