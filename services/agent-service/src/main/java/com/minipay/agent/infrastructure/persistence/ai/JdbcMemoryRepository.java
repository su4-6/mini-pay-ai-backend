package com.minipay.agent.infrastructure.persistence.ai;

import com.minipay.agent.application.port.MemoryRepository;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemoryRepository implements MemoryRepository {
    private final JdbcTemplate jdbc;

    public JdbcMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MemorySetting> findSetting(UUID userId) {
        List<MemorySetting> values = jdbc.query("""
                SELECT user_id, enabled, food_preference_enabled, allergen_avoidance_enabled,
                       meal_budget_enabled, contact_alias_enabled, address_alias_enabled,
                       version, created_at, updated_at
                FROM memory_setting
                WHERE user_id = ?
                """, (rs, row) -> new MemorySetting(
                uuid(rs.getBytes("user_id")), rs.getBoolean("enabled"),
                rs.getBoolean("food_preference_enabled"), rs.getBoolean("allergen_avoidance_enabled"),
                rs.getBoolean("meal_budget_enabled"), rs.getBoolean("contact_alias_enabled"),
                rs.getBoolean("address_alias_enabled"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))),
                bytes(userId));
        return values.stream().findFirst();
    }

    @Override
    public MemorySetting saveSetting(MemorySetting setting, long expectedVersion, Instant now) {
        byte[] userId = bytes(setting.userId());
        Long current = lockSettingVersion(userId);
        int changed;
        if (current == null) {
            if (expectedVersion != 0) throw new IllegalStateException("Memory setting version mismatch");
            changed = jdbc.update("""
                    INSERT INTO memory_setting (
                      user_id, enabled, food_preference_enabled, allergen_avoidance_enabled,
                      meal_budget_enabled, contact_alias_enabled, address_alias_enabled,
                      version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, userId, setting.enabled(), setting.foodPreferenceEnabled(),
                    setting.allergenAvoidanceEnabled(), setting.mealBudgetEnabled(),
                    setting.contactAliasEnabled(), setting.addressAliasEnabled(),
                    Timestamp.from(now), Timestamp.from(now));
        } else {
            if (current != expectedVersion) throw new IllegalStateException("Memory setting version mismatch");
            changed = jdbc.update("""
                    UPDATE memory_setting
                    SET enabled = ?, food_preference_enabled = ?, allergen_avoidance_enabled = ?,
                        meal_budget_enabled = ?, contact_alias_enabled = ?, address_alias_enabled = ?,
                        version = version + 1, updated_at = ?
                    WHERE user_id = ? AND version = ?
                    """, setting.enabled(), setting.foodPreferenceEnabled(),
                    setting.allergenAvoidanceEnabled(), setting.mealBudgetEnabled(),
                    setting.contactAliasEnabled(), setting.addressAliasEnabled(),
                    Timestamp.from(now), userId, expectedVersion);
        }
        if (changed != 1) throw new IllegalStateException("Memory setting update failed");
        return findSetting(setting.userId()).orElseThrow();
    }

    @Override
    public boolean isOwnedUserMessage(UUID userId, UUID messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM ai_message m
                JOIN ai_conversation c ON c.id = m.conversation_id
                WHERE m.id = ? AND m.role = 'USER' AND c.user_id = ? AND c.deleted_at IS NULL
                """, Integer.class, bytes(messageId), bytes(userId));
        return count != null && count == 1;
    }

    @Override
    public List<MemoryItem> listItems(UUID userId, MemoryType type, int limit) {
        if (type == null) {
            return jdbc.query("""
                    SELECT id, user_id, memory_type, display_value, reference_type, reference_id,
                           status, consent_message_id, consent_source, manual_idempotency_hash,
                           version, created_at, updated_at
                    FROM memory_item
                    WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """, JdbcMemoryRepository::mapItem, bytes(userId), limit);
        }
        return jdbc.query("""
                    SELECT id, user_id, memory_type, display_value, reference_type, reference_id,
                           status, consent_message_id, consent_source, manual_idempotency_hash,
                           version, created_at, updated_at
                FROM memory_item
                WHERE user_id = ? AND memory_type = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, JdbcMemoryRepository::mapItem, bytes(userId), type.name(), limit);
    }

    @Override
    public long countItems(UUID userId, MemoryType type) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM memory_item
                WHERE user_id = ? AND memory_type = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, Long.class, bytes(userId), type.name());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<MemoryItem> findByManualIdempotencyHash(UUID userId, byte[] idempotencyHash) {
        return jdbc.query("""
                SELECT id, user_id, memory_type, display_value, reference_type, reference_id,
                       status, consent_message_id, consent_source, manual_idempotency_hash,
                       version, created_at, updated_at
                FROM memory_item
                WHERE user_id = ? AND manual_idempotency_hash = ? AND deleted_at IS NULL
                """, JdbcMemoryRepository::mapItem, bytes(userId), idempotencyHash).stream().findFirst();
    }

    @Override
    public Optional<MemoryItem> findItem(UUID userId, UUID itemId) {
        List<MemoryItem> values = jdbc.query("""
                SELECT id, user_id, memory_type, display_value, reference_type, reference_id,
                       status, consent_message_id, consent_source, manual_idempotency_hash,
                       version, created_at, updated_at
                FROM memory_item
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, JdbcMemoryRepository::mapItem, bytes(itemId), bytes(userId));
        return values.stream().findFirst();
    }

    @Override
    public MemoryItem insertItem(MemoryItem item) {
        int changed = jdbc.update("""
                INSERT INTO memory_item (
                  id, user_id, memory_type, display_value, reference_type, reference_id,
                  status, consent_message_id, consent_source, manual_idempotency_hash,
                  version, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0, ?, ?, NULL)
                """, bytes(item.id()), bytes(item.userId()), item.type().name(), item.displayValue(),
                item.referenceType(), item.referenceId(), nullableBytes(item.consentMessageId()),
                item.consentSource(), item.manualIdempotencyHash(),
                Timestamp.from(item.createdAt()), Timestamp.from(item.updatedAt()));
        if (changed != 1) throw new IllegalStateException("Memory item insert failed");
        return item;
    }

    @Override
    public boolean updateItem(UUID userId, UUID itemId, String displayValue, String referenceType,
                              String referenceId, long expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE memory_item
                SET display_value = ?, reference_type = ?, reference_id = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                  AND version = ?
                """, displayValue, referenceType, referenceId, Timestamp.from(now),
                bytes(itemId), bytes(userId), expectedVersion) == 1;
    }

    @Override
    public boolean deleteItem(UUID userId, UUID itemId, long expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE memory_item
                SET status = 'DELETED', deleted_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                  AND version = ?
                """, Timestamp.from(now), Timestamp.from(now), bytes(itemId), bytes(userId),
                expectedVersion) == 1;
    }

    private Long lockSettingVersion(byte[] userId) {
        try {
            return jdbc.queryForObject("""
                    SELECT version FROM memory_setting WHERE user_id = ? FOR UPDATE
                    """, Long.class, userId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private static MemoryItem mapItem(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new MemoryItem(uuid(rs.getBytes("id")), uuid(rs.getBytes("user_id")),
                MemoryType.valueOf(rs.getString("memory_type")), rs.getString("display_value"),
                rs.getString("reference_type"), rs.getString("reference_id"), rs.getString("status"),
                uuid(rs.getBytes("consent_message_id")), rs.getString("consent_source"),
                rs.getBytes("manual_idempotency_hash"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static byte[] nullableBytes(UUID value) {
        return value == null ? null : bytes(value);
    }

    private static UUID uuid(byte[] value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static Instant instant(Timestamp value) {
        return value.toInstant();
    }
}
