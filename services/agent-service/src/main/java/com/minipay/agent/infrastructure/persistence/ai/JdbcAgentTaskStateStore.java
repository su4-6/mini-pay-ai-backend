package com.minipay.agent.infrastructure.persistence.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.port.AgentTaskStateStore;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentTaskStateStore implements AgentTaskStateStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAgentTaskStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PendingTask> findLatest(UUID userId, UUID conversationId, Instant notBefore) {
        return jdbc.query("""
                SELECT s.run_id, s.task_type, s.slots_json
                FROM agent_task_state s
                JOIN agent_run r ON r.id = s.run_id
                WHERE r.user_id = ? AND r.conversation_id = ?
                  AND r.status IN ('WAITING_INPUT', 'UNDERSTANDING')
                  AND s.updated_at >= ?
                ORDER BY s.updated_at DESC LIMIT 1
                """, (rs, row) -> new PendingTask(uuid(rs.getBytes("run_id")), rs.getString("task_type"),
                        read(rs.getString("slots_json"))), bytes(userId), bytes(conversationId),
                Timestamp.from(notBefore)).stream().findFirst();
    }

    @Override
    public Optional<PendingTask> findForRun(UUID userId, UUID runId, String taskType) {
        return jdbc.query("""
                SELECT s.run_id, s.task_type, s.slots_json
                FROM agent_task_state s
                JOIN agent_run r ON r.id = s.run_id
                WHERE r.user_id = ? AND r.id = ? AND s.task_type = ?
                LIMIT 1
                """, (rs, row) -> new PendingTask(uuid(rs.getBytes("run_id")), rs.getString("task_type"),
                        read(rs.getString("slots_json"))), bytes(userId), bytes(runId), taskType)
                .stream().findFirst();
    }

    @Override
    public void save(UUID runId, String taskType, Map<String, Object> slots, Instant now) {
        jdbc.update("""
                INSERT INTO agent_task_state
                    (run_id, task_type, state_version, slots_json, selected_resource_refs, updated_at)
                VALUES (?, ?, 0, ?, JSON_OBJECT(), ?)
                ON DUPLICATE KEY UPDATE task_type = VALUES(task_type), state_version = state_version + 1,
                    slots_json = VALUES(slots_json), updated_at = VALUES(updated_at)
                """, bytes(runId), taskType, write(slots), Timestamp.from(now));
    }

    @Override
    public void clear(UUID userId, UUID conversationId, String taskType) {
        jdbc.update("""
                DELETE s FROM agent_task_state s JOIN agent_run r ON r.id = s.run_id
                WHERE r.user_id = ? AND r.conversation_id = ? AND s.task_type = ?
                """, bytes(userId), bytes(conversationId), taskType);
    }

    private Map<String, Object> read(String value) {
        try { return objectMapper.readValue(value, MAP); }
        catch (Exception exception) { throw new IllegalStateException("Invalid task state", exception); }
    }

    private String write(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Invalid task state", exception); }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
