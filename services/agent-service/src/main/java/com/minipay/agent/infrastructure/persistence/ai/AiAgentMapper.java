package com.minipay.agent.infrastructure.persistence.ai;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiAgentMapper {
    @Insert("""
            INSERT INTO ai_conversation (
                id, user_id, title, status, version, next_message_sequence,
                last_message_at, created_at, updated_at, deleted_at
            ) VALUES (
                #{id}, #{userId}, #{title}, #{status}, #{version}, #{nextMessageSequence},
                #{lastMessageAt}, #{createdAt}, #{updatedAt}, #{deletedAt}
            )
            """)
    int insertConversation(AiConversationPo conversation);

    @Select("""
            SELECT id, user_id, title, status, version, next_message_sequence,
                   last_message_at, created_at, updated_at, deleted_at
            FROM ai_conversation
            WHERE user_id = #{userId} AND id = #{conversationId} AND deleted_at IS NULL
            """)
    AiConversationPo selectConversation(@Param("userId") byte[] userId,
                                        @Param("conversationId") byte[] conversationId);

    @Select("""
            <script>
            SELECT id, user_id, title, status, version, next_message_sequence,
                   last_message_at, created_at, updated_at, deleted_at
            FROM ai_conversation
            WHERE user_id = #{userId} AND deleted_at IS NULL
            <if test="beforeTime != null">
              AND (last_message_at &lt; #{beforeTime}
                   OR (last_message_at = #{beforeTime} AND id &lt; #{beforeId}))
            </if>
            ORDER BY last_message_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiConversationPo> selectConversations(@Param("userId") byte[] userId,
                                               @Param("beforeTime") LocalDateTime beforeTime,
                                               @Param("beforeId") byte[] beforeId,
                                               @Param("limit") int limit);

    @Update("""
            UPDATE ai_conversation
            SET title = #{title}, version = version + 1, updated_at = #{now}
            WHERE user_id = #{userId} AND id = #{conversationId}
              AND version = #{expectedVersion} AND deleted_at IS NULL
            """)
    int renameConversation(@Param("userId") byte[] userId,
                           @Param("conversationId") byte[] conversationId,
                           @Param("title") String title,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_conversation
            SET title = #{title}, version = version + 1, updated_at = #{now}
            WHERE user_id = #{userId} AND id = #{conversationId}
              AND title IN ('新对话', '未命名对话') AND next_message_sequence = 2
              AND deleted_at IS NULL
            """)
    int assignInitialTitle(@Param("userId") byte[] userId,
                           @Param("conversationId") byte[] conversationId,
                           @Param("title") String title,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_conversation
            SET status = 'DELETED', deleted_at = #{now}, version = version + 1, updated_at = #{now}
            WHERE user_id = #{userId} AND id = #{conversationId} AND deleted_at IS NULL
            """)
    int softDeleteConversation(@Param("userId") byte[] userId,
                               @Param("conversationId") byte[] conversationId,
                               @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, user_id, title, status, version, next_message_sequence,
                   last_message_at, created_at, updated_at, deleted_at
            FROM ai_conversation
            WHERE user_id = #{userId} AND id = #{conversationId} AND deleted_at IS NULL
            FOR UPDATE
            """)
    AiConversationPo lockConversation(@Param("userId") byte[] userId,
                                      @Param("conversationId") byte[] conversationId);

    @Update("""
            UPDATE ai_conversation
            SET next_message_sequence = #{nextSequence}, last_message_at = #{now},
                version = version + 1, updated_at = #{now}
            WHERE id = #{conversationId}
            """)
    int updateConversationSequence(@Param("conversationId") byte[] conversationId,
                                   @Param("nextSequence") long nextSequence,
                                   @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO ai_message (
                id, conversation_id, run_id, role, content_text, card_type,
                card_version, card_payload, sequence_no, created_at
            ) VALUES (
                #{id}, #{conversationId}, #{runId}, #{role}, #{contentText}, #{cardType},
                #{cardVersion}, #{cardPayload}, #{sequenceNo}, #{createdAt}
            )
            """)
    int insertMessage(AiMessagePo message);

    @Select("""
            <script>
            SELECT m.id, m.conversation_id, m.run_id, m.role, m.content_text,
                   m.card_type, m.card_version, m.card_payload, m.sequence_no, m.created_at
            FROM ai_message m
            JOIN ai_conversation c ON c.id = m.conversation_id
            WHERE c.user_id = #{userId} AND c.id = #{conversationId} AND c.deleted_at IS NULL
            <if test="beforeSequence != null">
              AND m.sequence_no &lt; #{beforeSequence}
            </if>
            ORDER BY m.sequence_no DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiMessagePo> selectMessages(@Param("userId") byte[] userId,
                                     @Param("conversationId") byte[] conversationId,
                                     @Param("beforeSequence") Long beforeSequence,
                                     @Param("limit") int limit);

    @Insert("""
            INSERT INTO agent_run (
                id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                request_hash, context_version, status, intent_type, business_ref_type,
                business_ref_id, model_provider, model_name, prompt_version,
                tool_catalog_version, next_event_sequence, cancel_requested, version,
                created_at, started_at, completed_at, updated_at
            ) VALUES (
                #{id}, #{conversationId}, #{userId}, #{clientMessageId}, #{idempotencyKeyHash},
                #{requestHash}, #{contextVersion}, #{status}, #{intentType}, #{businessRefType},
                #{businessRefId}, #{modelProvider}, #{modelName}, #{promptVersion},
                #{toolCatalogVersion}, #{nextEventSequence}, #{cancelRequested}, #{version},
                #{createdAt}, #{startedAt}, #{completedAt}, #{updatedAt}
            )
            """)
    int insertRun(AgentRunPo run);

    @Select("""
            SELECT id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                   request_hash, context_version, status, intent_type, business_ref_type,
                   business_ref_id, model_provider, model_name, prompt_version,
                   tool_catalog_version, next_event_sequence, cancel_requested, version,
                   created_at, started_at, completed_at, updated_at
            FROM agent_run
            WHERE user_id = #{userId} AND id = #{runId}
            """)
    AgentRunPo selectRun(@Param("userId") byte[] userId, @Param("runId") byte[] runId);

    @Select("""
            SELECT id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                   request_hash, context_version, status, intent_type, business_ref_type,
                   business_ref_id, model_provider, model_name, prompt_version,
                   tool_catalog_version, next_event_sequence, cancel_requested, version,
                   created_at, started_at, completed_at, updated_at
            FROM agent_run
            WHERE user_id = #{userId} AND conversation_id = #{conversationId}
              AND idempotency_key_hash = #{idempotencyKeyHash}
            """)
    AgentRunPo selectRunByIdempotencyHash(@Param("userId") byte[] userId,
                                          @Param("conversationId") byte[] conversationId,
                                          @Param("idempotencyKeyHash") byte[] idempotencyKeyHash);

    @Insert("""
            INSERT INTO agent_user_run_gate (user_id, updated_at)
            VALUES (#{userId}, #{now})
            ON DUPLICATE KEY UPDATE updated_at = updated_at
            """)
    int insertUserRunGate(@Param("userId") byte[] userId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT 1 FROM agent_user_run_gate WHERE user_id = #{userId} FOR UPDATE
            """)
    Integer lockUserRunGate(@Param("userId") byte[] userId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM agent_run
            WHERE user_id = #{userId}
              AND status IN ('RECEIVED', 'UNDERSTANDING', 'EXECUTING_TOOL')
            <if test="excludedRunId != null">AND id != #{excludedRunId}</if>
            </script>
            """)
    int countActiveRuns(@Param("userId") byte[] userId, @Param("excludedRunId") byte[] excludedRunId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM agent_run
            WHERE user_id = #{userId} AND conversation_id = #{conversationId}
              AND status IN ('RECEIVED', 'UNDERSTANDING', 'EXECUTING_TOOL')
            <if test="excludedRunId != null">AND id != #{excludedRunId}</if>
            </script>
            """)
    int countActiveConversationRuns(@Param("userId") byte[] userId,
                                    @Param("conversationId") byte[] conversationId,
                                    @Param("excludedRunId") byte[] excludedRunId);

    @Select("""
            SELECT id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                   request_hash, context_version, status, intent_type, business_ref_type,
                   business_ref_id, model_provider, model_name, prompt_version,
                   tool_catalog_version, next_event_sequence, cancel_requested, version,
                   created_at, started_at, completed_at, updated_at
            FROM agent_run
            WHERE user_id = #{userId}
              AND status IN ('RECEIVED', 'UNDERSTANDING', 'EXECUTING_TOOL')
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<AgentRunPo> selectActiveRuns(@Param("userId") byte[] userId, @Param("limit") int limit);

    @Select("""
            SELECT id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                   request_hash, context_version, status, intent_type, business_ref_type,
                   business_ref_id, model_provider, model_name, prompt_version,
                   tool_catalog_version, next_event_sequence, cancel_requested, version,
                   created_at, started_at, completed_at, updated_at
            FROM agent_run
            WHERE status IN ('RECEIVED', 'UNDERSTANDING', 'EXECUTING_TOOL')
              AND updated_at < #{updatedBefore}
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<AgentRunPo> selectStaleActiveRuns(@Param("updatedBefore") LocalDateTime updatedBefore,
                                           @Param("limit") int limit);

    @Update("""
            UPDATE agent_run
            SET status = #{target}, started_at = COALESCE(started_at, #{startedAt}),
                completed_at = #{completedAt}, version = version + 1, updated_at = #{now}
            WHERE user_id = #{userId} AND id = #{runId} AND status = #{source}
              AND version = #{expectedVersion}
            """)
    int transitionRun(@Param("userId") byte[] userId,
                      @Param("runId") byte[] runId,
                      @Param("source") String source,
                      @Param("target") String target,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("startedAt") LocalDateTime startedAt,
                      @Param("completedAt") LocalDateTime completedAt,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_run
            SET cancel_requested = 1, updated_at = #{now}
            WHERE user_id = #{userId} AND id = #{runId}
              AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
            """)
    int requestCancellation(@Param("userId") byte[] userId,
                            @Param("runId") byte[] runId,
                            @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, conversation_id, user_id, client_message_id, idempotency_key_hash,
                   request_hash, context_version, status, intent_type, business_ref_type,
                   business_ref_id, model_provider, model_name, prompt_version,
                   tool_catalog_version, next_event_sequence, cancel_requested, version,
                   created_at, started_at, completed_at, updated_at
            FROM agent_run
            WHERE id = #{runId}
            FOR UPDATE
            """)
    AgentRunPo lockRun(@Param("runId") byte[] runId);

    @Update("""
            UPDATE agent_run
            SET next_event_sequence = #{nextSequence}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    int updateRunEventSequence(@Param("runId") byte[] runId,
                               @Param("nextSequence") long nextSequence,
                               @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO agent_run_event (
                id, run_id, sequence_no, event_type, payload_version,
                payload, occurred_at, expires_at
            ) VALUES (
                #{id}, #{runId}, #{sequenceNo}, #{eventType}, #{payloadVersion},
                #{payload}, #{occurredAt}, #{expiresAt}
            )
            """)
    int insertRunEvent(AgentRunEventPo event);

    @Select("""
            SELECT e.id, e.run_id, e.sequence_no, e.event_type, e.payload_version,
                   e.payload, e.occurred_at, e.expires_at
            FROM agent_run_event e
            JOIN agent_run r ON r.id = e.run_id
            WHERE r.user_id = #{userId} AND e.run_id = #{runId}
              AND e.sequence_no > #{afterSequence} AND e.expires_at > #{now}
            ORDER BY e.sequence_no
            LIMIT #{limit}
            """)
    List<AgentRunEventPo> selectRunEvents(@Param("userId") byte[] userId,
                                          @Param("runId") byte[] runId,
                                          @Param("afterSequence") long afterSequence,
                                          @Param("now") LocalDateTime now,
                                          @Param("limit") int limit);

    @Insert("""
            INSERT INTO agent_tool_trace (
              id, run_id, tool_name, risk_level, schema_version, request_digest,
              result_digest, result_code, latency_ms, created_at, completed_at
            ) VALUES (#{id}, #{runId}, #{toolName}, #{riskLevel}, #{schemaVersion},
                      #{requestDigest}, NULL, NULL, NULL, #{createdAt}, NULL)
            """)
    int insertToolTrace(
            @Param("id") byte[] id,
            @Param("runId") byte[] runId,
            @Param("toolName") String toolName,
            @Param("riskLevel") String riskLevel,
            @Param("schemaVersion") int schemaVersion,
            @Param("requestDigest") byte[] requestDigest,
            @Param("createdAt") LocalDateTime createdAt);

    @Update("""
            UPDATE agent_tool_trace
            SET result_digest = #{resultDigest}, result_code = #{resultCode},
                latency_ms = GREATEST(0, TIMESTAMPDIFF(MICROSECOND, created_at, #{completedAt}) DIV 1000),
                completed_at = #{completedAt}
            WHERE id = #{id} AND completed_at IS NULL
            """)
    int completeToolTrace(
            @Param("id") byte[] id,
            @Param("resultDigest") byte[] resultDigest,
            @Param("resultCode") String resultCode,
            @Param("latencyMs") long latencyMs,
            @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE agent_tool_trace
            SET result_digest = #{resultDigest}, result_code = #{resultCode},
                latency_ms = GREATEST(0, TIMESTAMPDIFF(MICROSECOND, created_at, #{completedAt}) DIV 1000),
                completed_at = #{completedAt}
            WHERE run_id = #{runId} AND completed_at IS NULL
            """)
    int completeOpenToolTraces(@Param("runId") byte[] runId,
                               @Param("resultDigest") byte[] resultDigest,
                               @Param("resultCode") String resultCode,
                               @Param("completedAt") LocalDateTime completedAt);
}
