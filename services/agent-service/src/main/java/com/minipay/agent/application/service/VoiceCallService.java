package com.minipay.agent.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.domain.model.ChatMessage;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import com.minipay.agent.infrastructure.realtime.RealtimeSessionRegistry;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceCallService {
    private final JdbcTemplate jdbc; private final ChatRepository chats; private final RealtimeSessionRegistry realtime;
    private final ObjectMapper json; private final Duration ringTimeout; private final List<String> turnUrls;
    private final String turnSecret; private final Duration turnTtl;
    public VoiceCallService(JdbcTemplate jdbc, ChatRepository chats, RealtimeSessionRegistry realtime, ObjectMapper json,
            @Value("${minipay.agent.calls.ring-timeout:PT30S}") Duration ringTimeout,
            @Value("${minipay.agent.calls.turn-urls:stun:stun.l.google.com:19302}") String turnUrls,
            @Value("${minipay.agent.calls.turn-shared-secret:}") String turnSecret,
            @Value("${minipay.agent.calls.turn-credential-ttl:PT1H}") Duration turnTtl) {
        this.jdbc = jdbc; this.chats = chats; this.realtime = realtime; this.json = json; this.ringTimeout = ringTimeout;
        this.turnUrls = java.util.Arrays.stream(turnUrls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(); this.turnSecret = turnSecret; this.turnTtl = turnTtl;
    }
    @Transactional public Call create(UUID caller, String conversationId) {
        UUID callee = chats.findDirectPeer(caller, conversationId).orElseThrow(() -> new CallException("DIRECT_CONVERSATION_REQUIRED"));
        if (!realtime.isOnline(callee)) throw new CallException("CALLEE_OFFLINE");
        if (hasActive(caller) || hasActive(callee)) throw new CallException("CALLEE_BUSY");
        Call call = new Call(UUID.randomUUID(), conversationId, caller, callee, "RINGING", Instant.now(), null, null);
        jdbc.update("INSERT INTO voice_call (id, conversation_id, caller_id, callee_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?)", bytes(call.id()), conversationId, bytes(caller), bytes(callee), call.status(), Timestamp.from(call.createdAt()));
        emit(call, "CALL_INVITE"); return call;
    }
    public Call accept(UUID actor, UUID id) { return transition(actor, id, "ACCEPTED"); }
    public Call reject(UUID actor, UUID id) { return transition(actor, id, "REJECTED"); }
    public Call cancel(UUID actor, UUID id) { return transition(actor, id, "CANCELLED"); }
    public Call end(UUID actor, UUID id) { return transition(actor, id, "ENDED"); }
    @Transactional protected Call transition(UUID actor, UUID id, String target) {
        Call current = find(id); requireParticipant(actor, current); if (isTerminal(current.status())) return current;
        if ("ACCEPTED".equals(target) && (!actor.equals(current.calleeId()) || !"RINGING".equals(current.status()))) throw new CallException("INVALID_CALL_STATE");
        if ("REJECTED".equals(target) && !actor.equals(current.calleeId())) throw new CallException("INVALID_CALL_STATE");
        if ("CANCELLED".equals(target) && !actor.equals(current.callerId())) throw new CallException("INVALID_CALL_STATE");
        Instant now = Instant.now();
        jdbc.update("UPDATE voice_call SET status = ?, answered_at = COALESCE(?, answered_at), ended_at = COALESCE(?, ended_at) WHERE id = ?", target,
                "ACCEPTED".equals(target) ? Timestamp.from(now) : null, isTerminal(target) ? Timestamp.from(now) : null, bytes(id));
        Call updated = find(id); emit(updated, "CALL_STATUS"); if (isTerminal(target)) insertCallMessage(updated); return updated;
    }
    public UUID peerForSignal(UUID actor, UUID id) { Call call = find(id); requireParticipant(actor, call); if (!List.of("RINGING", "ACCEPTED").contains(call.status())) throw new CallException("INVALID_CALL_STATE"); return actor.equals(call.callerId()) ? call.calleeId() : call.callerId(); }
    public Call findFor(UUID actor, UUID id) { Call call = find(id); requireParticipant(actor, call); return call; }
    public IceConfiguration ice(UUID userId) { long expires = Instant.now().plus(turnTtl).getEpochSecond(); String username = expires + ":" + userId; String credential = turnSecret.isBlank() ? null : hmac(username, turnSecret); return new IceConfiguration(turnUrls, credential == null ? null : username, credential, expires); }
    @Scheduled(fixedDelay = 5000) @Transactional void expireMissedCalls() {
        List<Call> calls = jdbc.query("SELECT * FROM voice_call WHERE status = 'RINGING' AND created_at < ?", (rs, row) -> map(rs), Timestamp.from(Instant.now().minus(ringTimeout)));
        for (Call call : calls) if (jdbc.update("UPDATE voice_call SET status = 'MISSED', ended_at = ? WHERE id = ? AND status = 'RINGING'", Timestamp.from(Instant.now()), bytes(call.id())) > 0) { Call updated = find(call.id()); emit(updated, "CALL_STATUS"); insertCallMessage(updated); }
    }
    private boolean hasActive(UUID user) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM voice_call WHERE (caller_id = ? OR callee_id = ?) AND status IN ('RINGING','ACCEPTED')", Integer.class, bytes(user), bytes(user)); return count != null && count > 0; }
    private void emit(Call call, String type) { var payload = json.valueToTree(java.util.Map.of("status", call.status(), "callerId", call.callerId().toString(), "calleeId", call.calleeId().toString())); realtime.publish(call.callerId(), type, call.id(), call.conversationId(), payload); realtime.publish(call.calleeId(), type, call.id(), call.conversationId(), payload); }
    private void insertCallMessage(Call call) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE call_id = ?", Integer.class, bytes(call.id())); if (exists != null && exists > 0) return;
        int duration = call.answeredAt() == null || call.endedAt() == null ? 0 : (int) Duration.between(call.answeredAt(), call.endedAt()).toSeconds();
        String content = switch (call.status()) { case "REJECTED" -> "已拒绝"; case "MISSED" -> "未接听"; case "CANCELLED" -> "已取消"; default -> "语音通话 " + String.format("%02d:%02d", duration / 60, duration % 60); };
        ChatMessage message = chats.insertMessage(new ChatMessage(null, call.conversationId(), call.callerId(), "Me", content, "Call", null, null, null, null, null, null, null, null, null, null, null, null, null, call.id(), call.status(), duration, Instant.now()));
        var payload = json.valueToTree(java.util.Map.of("messageId", message.id()));
        realtime.publish(call.callerId(), "CHAT_MESSAGE_CREATED", null, call.conversationId(), payload);
        realtime.publish(call.calleeId(), "CHAT_MESSAGE_CREATED", null, call.conversationId(), payload);
    }
    private Call find(UUID id) { List<Call> values = jdbc.query("SELECT * FROM voice_call WHERE id = ?", (rs, row) -> map(rs), bytes(id)); if (values.isEmpty()) throw new CallException("CALL_NOT_FOUND"); return values.get(0); }
    private static Call map(java.sql.ResultSet rs) throws java.sql.SQLException { Timestamp answered = rs.getTimestamp("answered_at"), ended = rs.getTimestamp("ended_at"); return new Call(uuid(rs.getBytes("id")), rs.getString("conversation_id"), uuid(rs.getBytes("caller_id")), uuid(rs.getBytes("callee_id")), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), answered == null ? null : answered.toInstant(), ended == null ? null : ended.toInstant()); }
    private static void requireParticipant(UUID actor, Call call) { if (!actor.equals(call.callerId()) && !actor.equals(call.calleeId())) throw new CallException("CALL_FORBIDDEN"); }
    private static boolean isTerminal(String status) { return List.of("REJECTED", "CANCELLED", "MISSED", "ENDED").contains(status); }
    private static String hmac(String value, String secret) { try { Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1")); return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("TURN credential generation failed", e); } }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
    public record Call(UUID id, String conversationId, UUID callerId, UUID calleeId, String status, Instant createdAt, Instant answeredAt, Instant endedAt) {}
    public record IceConfiguration(List<String> urls, String username, String credential, long expiresAtEpochSeconds) {}
    public static final class CallException extends RuntimeException { public final String code; public CallException(String code) { super(code); this.code = code; } }
}
