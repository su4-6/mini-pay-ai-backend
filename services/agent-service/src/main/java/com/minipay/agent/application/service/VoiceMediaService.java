package com.minipay.agent.application.service;

import com.minipay.agent.application.port.VoiceObjectStorage;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceMediaService {
    public static final String CONTENT_TYPE = "audio/mp4";
    public static final long MAX_BYTES = 1024L * 1024L;
    private final JdbcTemplate jdbc;
    private final ChatRepository chats;
    private final VoiceObjectStorage storage;
    private final Duration uploadTtl;
    private final Duration readTtl;

    public VoiceMediaService(JdbcTemplate jdbc, ChatRepository chats, VoiceObjectStorage storage,
            @Value("${minipay.agent.object-storage.upload-ttl:PT10M}") Duration uploadTtl,
            @Value("${minipay.agent.object-storage.read-ttl:PT5M}") Duration readTtl) {
        this.jdbc = jdbc; this.chats = chats; this.storage = storage;
        this.uploadTtl = uploadTtl; this.readTtl = readTtl;
    }

    @Transactional
    public Upload createUpload(UUID owner, String conversationId, String contentType, long size, String sha256) {
        if (!chats.canAccessConversation(owner, conversationId)) throw new VoiceMediaException("CONVERSATION_FORBIDDEN");
        if (!CONTENT_TYPE.equals(contentType) || size < 1 || size > MAX_BYTES || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new VoiceMediaException("INVALID_VOICE_MEDIA");
        }
        UUID id = UUID.randomUUID();
        String key = "chat/voice/" + owner + "/" + id + ".m4a";
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO chat_voice_media
                    (id, owner_user_id, conversation_id, object_key, content_type, sha256,
                     size_bytes, duration_ms, status, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', ?, NULL)
                """, bytes(id), bytes(owner), conversationId, key, contentType,
                sha256.toLowerCase(), size, Timestamp.from(now));
        var signed = storage.signUpload(key, contentType, sha256.toLowerCase(), uploadTtl);
        return new Upload(id, signed.url().toString(), signed.requiredHeaders(), signed.expiresAt());
    }

    @Transactional
    public Media complete(UUID owner, UUID mediaId, int durationMs) {
        Media media = find(mediaId);
        if (!owner.equals(media.ownerId())) throw new VoiceMediaException("VOICE_MEDIA_FORBIDDEN");
        if (durationMs < 1000 || durationMs > 60_000) throw new VoiceMediaException("INVALID_VOICE_DURATION");
        if ("READY".equals(media.status())) return media;
        var object = storage.head(media.objectKey());
        if (object.size() != media.sizeBytes() || object.size() > MAX_BYTES
                || !CONTENT_TYPE.equals(object.contentType())
                || object.sha256() == null || !object.sha256().equalsIgnoreCase(media.sha256())) {
            storage.delete(media.objectKey());
            throw new VoiceMediaException("VOICE_OBJECT_MISMATCH");
        }
        jdbc.update("UPDATE chat_voice_media SET status = 'READY', duration_ms = ?, completed_at = ? WHERE id = ? AND status = 'PENDING'",
                durationMs, Timestamp.from(Instant.now()), bytes(mediaId));
        return new Media(media.id(), media.ownerId(), media.conversationId(), media.objectKey(), media.contentType(),
                media.sha256(), media.sizeBytes(), durationMs, "READY");
    }

    public Media requireReady(UUID owner, UUID mediaId, String conversationId, int durationMs) {
        Media media = find(mediaId);
        if (!owner.equals(media.ownerId()) || !conversationId.equals(media.conversationId())
                || !"READY".equals(media.status()) || media.durationMs() == null || media.durationMs() != durationMs) {
            throw new VoiceMediaException("VOICE_MEDIA_NOT_READY");
        }
        return media;
    }

    public Playback playback(UUID viewer, UUID mediaId) {
        Media media = find(mediaId);
        if (!"READY".equals(media.status()) || !chats.canAccessConversation(viewer, media.conversationId())) {
            throw new VoiceMediaException("VOICE_MEDIA_FORBIDDEN");
        }
        var signed = storage.signRead(media.objectKey(), readTtl);
        return new Playback(signed.url().toString(), signed.expiresAt());
    }

    private Media find(UUID id) {
        List<Media> rows = jdbc.query("""
                SELECT id, owner_user_id, conversation_id, object_key, content_type, sha256,
                       size_bytes, duration_ms, status FROM chat_voice_media WHERE id = ?
                """, (rs, row) -> new Media(uuid(rs.getBytes("id")), uuid(rs.getBytes("owner_user_id")),
                rs.getString("conversation_id"), rs.getString("object_key"), rs.getString("content_type"),
                rs.getString("sha256"), rs.getLong("size_bytes"), (Integer) rs.getObject("duration_ms"), rs.getString("status")), bytes(id));
        if (rows.isEmpty()) throw new VoiceMediaException("VOICE_MEDIA_NOT_FOUND");
        return rows.get(0);
    }

    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }

    public record Upload(UUID mediaId, String uploadUrl, java.util.Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record Playback(String playbackUrl, Instant expiresAt) {}
    public record Media(UUID id, UUID ownerId, String conversationId, String objectKey, String contentType,
                        String sha256, long sizeBytes, Integer durationMs, String status) {}
}
