package com.minipay.agent.infrastructure.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RealtimeSessionRegistry {
    public static final String CHANNEL = "agent:realtime";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(90);
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RealtimeSessionRegistry(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    public void connected(UUID userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        redis.opsForSet().add(presenceSet(userId), session.getId());
        refresh(userId, session);
    }
    public void disconnected(UUID userId, WebSocketSession session) {
        Set<WebSocketSession> values = sessions.get(userId);
        if (values != null) { values.remove(session); if (values.isEmpty()) sessions.remove(userId); }
        redis.opsForSet().remove(presenceSet(userId), session.getId());
        redis.delete(presenceKey(userId, session.getId()));
    }
    public boolean isOnline(UUID userId) {
        Set<String> ids = redis.opsForSet().members(presenceSet(userId));
        if (ids == null || ids.isEmpty()) return false;
        boolean online = false;
        for (String id : ids) {
            if (Boolean.TRUE.equals(redis.hasKey(presenceKey(userId, id)))) online = true;
            else redis.opsForSet().remove(presenceSet(userId), id);
        }
        return online;
    }
    public void publish(UUID targetUserId, String type, UUID callId, String conversationId, JsonNode payload) {
        try { redis.convertAndSend(CHANNEL, json.writeValueAsString(new RealtimeEnvelope(UUID.randomUUID(), type, targetUserId, callId, conversationId, System.currentTimeMillis(), payload))); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Realtime event serialization failed", e); }
    }
    public void deliver(String raw) {
        try {
            RealtimeEnvelope envelope = json.readValue(raw, RealtimeEnvelope.class);
            String body = json.writeValueAsString(envelope);
            for (WebSocketSession session : sessions.getOrDefault(envelope.targetUserId(), Set.of())) {
                if (session.isOpen()) try { synchronized (session) { session.sendMessage(new TextMessage(body)); } } catch (IOException ignored) { }
            }
        } catch (JsonProcessingException ignored) { }
    }
    @Scheduled(fixedDelay = 30000) void refreshPresence() { sessions.forEach((user, values) -> values.forEach(session -> refresh(user, session))); }
    private void refresh(UUID userId, WebSocketSession session) { redis.opsForValue().set(presenceKey(userId, session.getId()), "1", PRESENCE_TTL); }
    private static String presenceSet(UUID userId) { return "agent:presence:" + userId; }
    private static String presenceKey(UUID userId, String sessionId) { return presenceSet(userId) + ":" + sessionId; }
    public record RealtimeEnvelope(UUID eventId, String type, UUID targetUserId, UUID callId, String conversationId, long occurredAt, JsonNode payload) {}
}
