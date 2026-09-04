package com.minipay.agent.application.service;

import com.minipay.agent.application.port.MemoryRepository;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryApplicationService {
    private static final Pattern SAFE_ADDRESS_ALIAS = Pattern.compile("[\\p{IsHan}A-Za-z]{1,12}");
    private static final int MAX_CUSTOM_ITEMS = 100;
    private final MemoryRepository repository;
    private final MemoryContentPolicy contentPolicy;
    private final Clock clock = Clock.systemUTC();

    public MemoryApplicationService(MemoryRepository repository, MemoryContentPolicy contentPolicy) {
        this.repository = repository;
        this.contentPolicy = contentPolicy;
    }

    @Transactional(readOnly = true)
    public MemorySetting setting(UUID userId) {
        return repository.findSetting(userId).orElseGet(() -> MemorySetting.disabled(userId, clock.instant()));
    }

    @Transactional
    public MemorySetting updateSetting(UUID userId, boolean enabled, boolean foodPreference,
                                       boolean allergenAvoidance, boolean mealBudget,
                                       boolean contactAlias, boolean addressAlias, long expectedVersion) {
        Instant now = clock.instant();
        MemorySetting next = new MemorySetting(userId, enabled, enabled && foodPreference,
                enabled && allergenAvoidance, enabled && mealBudget, enabled && contactAlias,
                enabled && addressAlias, expectedVersion + 1, now, now);
        try {
            return repository.saveSetting(next, expectedVersion, now);
        } catch (IllegalStateException exception) {
            throw new AgentApplicationException("AGENT_MEMORY_VERSION_CONFLICT", "记忆设置已更新，请刷新后重试");
        }
    }

    @Transactional(readOnly = true)
    public List<MemoryItem> list(UUID userId, MemoryType type, int limit) {
        return repository.listItems(userId, type, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public MemoryItem create(UUID userId, MemoryType type, String value, String referenceType,
                             String referenceId, UUID consentMessageId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        byte[] idempotencyHash = digest(idempotencyKey);
        MemoryItem replay = repository.findByManualIdempotencyHash(userId, idempotencyHash).orElse(null);
        if (replay != null) {
            if (replay.type() != type || !replay.displayValue().equals(contentPolicy.validateAndNormalize(value))) {
                throw new AgentApplicationException("AGENT_MEMORY_IDEMPOTENCY_KEY_REUSED",
                        "幂等键已用于不同的记忆内容");
            }
            return replay;
        }
        validateConsentAndPermission(userId, type, consentMessageId);
        if (type == MemoryType.CUSTOM && repository.countItems(userId, type) >= MAX_CUSTOM_ITEMS) {
            throw new AgentApplicationException("AGENT_MEMORY_LIMIT_REACHED", "自由记忆最多保存 100 条");
        }
        NormalizedMemory normalized = normalize(type, value, referenceType, referenceId);
        Instant now = clock.instant();
        return repository.insertItem(new MemoryItem(UuidV7.generate(), userId, type,
                normalized.value(), normalized.referenceType(), normalized.referenceId(), "ACTIVE",
                consentMessageId, consentMessageId == null ? "PROFILE_MANUAL" : "CHAT_EXPLICIT",
                idempotencyHash, 0, now, now));
    }

    @Transactional
    public MemoryItem update(UUID userId, UUID itemId, String value, String referenceType,
                             String referenceId, UUID consentMessageId, long expectedVersion) {
        MemoryItem current = repository.findItem(userId, itemId)
                .orElseThrow(() -> new AgentApplicationException("AGENT_MEMORY_NOT_FOUND", "记忆不存在"));
        validateConsentAndPermission(userId, current.type(), consentMessageId);
        NormalizedMemory normalized = normalize(current.type(), value, referenceType, referenceId);
        if (!repository.updateItem(userId, itemId, normalized.value(), normalized.referenceType(),
                normalized.referenceId(), expectedVersion, clock.instant())) {
            throw new AgentApplicationException("AGENT_MEMORY_VERSION_CONFLICT", "记忆已更新，请刷新后重试");
        }
        return repository.findItem(userId, itemId).orElseThrow();
    }

    @Transactional
    public void delete(UUID userId, UUID itemId, long expectedVersion) {
        if (!repository.deleteItem(userId, itemId, expectedVersion, clock.instant())) {
            throw new AgentApplicationException("AGENT_MEMORY_VERSION_CONFLICT", "记忆不存在或已更新");
        }
    }

    private void validateConsentAndPermission(UUID userId, MemoryType type, UUID consentMessageId) {
        if (!setting(userId).permits(type)) {
            throw new AgentApplicationException("AGENT_MEMORY_CONSENT_REQUIRED", "请先开启对应类型的长期记忆授权");
        }
        if (consentMessageId != null && !repository.isOwnedUserMessage(userId, consentMessageId)) {
            throw new AgentApplicationException("AGENT_MEMORY_CONSENT_INVALID", "必须绑定本人的明确同意消息");
        }
    }

    private NormalizedMemory normalize(
            MemoryType type, String value, String referenceType, String referenceId) {
        String normalizedValue = contentPolicy.validateAndNormalize(value);
        if (type == MemoryType.ADDRESS_ALIAS) {
            if (!SAFE_ADDRESS_ALIAS.matcher(normalizedValue).matches()) {
                throw new AgentApplicationException("AGENT_MEMORY_VALUE_REJECTED", "地址记忆只能保存简短别名，不能保存详细地址");
            }
            requireReference(referenceType, referenceId, "DELIVERY_ADDRESS");
        } else if (type == MemoryType.CONTACT_ALIAS) {
            requireReference(referenceType, referenceId, "CONTACT_RELATION");
        } else if (referenceType != null || referenceId != null) {
            throw new AgentApplicationException("AGENT_MEMORY_REFERENCE_INVALID", "该记忆类型不接受业务引用");
        }
        return new NormalizedMemory(normalizedValue,
                referenceType == null ? null : referenceType.strip().toUpperCase(Locale.ROOT),
                referenceId == null ? null : referenceId.strip());
    }

    private static void requireReference(String referenceType, String referenceId, String expectedType) {
        if (referenceType == null || !expectedType.equals(referenceType.strip().toUpperCase(Locale.ROOT))
                || referenceId == null) {
            throw new AgentApplicationException("AGENT_MEMORY_REFERENCE_INVALID", "记忆缺少有效的业务引用");
        }
        try {
            UUID.fromString(referenceId.strip());
        } catch (IllegalArgumentException exception) {
            throw new AgentApplicationException("AGENT_MEMORY_REFERENCE_INVALID", "业务引用必须是 UUID");
        }
    }

    private record NormalizedMemory(String value, String referenceType, String referenceId) {
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 128) {
            throw new AgentApplicationException("AGENT_IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 长度必须为 8 到 128 个字符");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
