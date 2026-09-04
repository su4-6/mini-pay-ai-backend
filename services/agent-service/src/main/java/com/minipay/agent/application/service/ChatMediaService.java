package com.minipay.agent.application.service;

import com.minipay.agent.application.port.VoiceObjectStorage;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMediaService {
    public static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    public static final long MAX_VIDEO_BYTES = 100L * 1024L * 1024L;
    public static final int MAX_VIDEO_DURATION_MS = 60_000;
    private static final int MAX_DIMENSION = 8192;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

    private final JdbcTemplate jdbc;
    private final ChatRepository chats;
    private final VoiceObjectStorage storage;
    private final Duration uploadTtl;
    private final Duration readTtl;

    public ChatMediaService(JdbcTemplate jdbc, ChatRepository chats, VoiceObjectStorage storage,
            @Value("${minipay.agent.object-storage.upload-ttl:PT10M}") Duration uploadTtl,
            @Value("${minipay.agent.object-storage.read-ttl:PT5M}") Duration readTtl) {
        this.jdbc = jdbc;
        this.chats = chats;
        this.storage = storage;
        this.uploadTtl = uploadTtl;
        this.readTtl = readTtl;
    }

    @Transactional
    public Upload createUpload(UUID owner, String conversationId, String kindValue,
            String contentTypeValue, long size, String sha256) {
        if (!chats.canAccessConversation(owner, conversationId)) {
            throw new VoiceMediaException("CONVERSATION_FORBIDDEN");
        }
        String kind = normalizeKind(kindValue);
        String contentType = contentTypeValue == null ? "" : contentTypeValue.toLowerCase(Locale.ROOT);
        long maxBytes = "Image".equals(kind) ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
        Set<String> allowedTypes = "Image".equals(kind) ? IMAGE_TYPES : VIDEO_TYPES;
        if (!allowedTypes.contains(contentType) || size < 1 || size > maxBytes
                || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new VoiceMediaException("INVALID_CHAT_MEDIA");
        }
        UUID id = UUID.randomUUID();
        String extension = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "mp4";
        };
        String key = "chat/media/" + owner + "/" + id + "." + extension;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO chat_media
                    (id, owner_user_id, conversation_id, media_kind, object_key, content_type,
                     sha256, size_bytes, width_px, height_px, duration_ms, status, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'PENDING', ?, NULL)
                """, bytes(id), bytes(owner), conversationId, kind, key, contentType,
                sha256.toLowerCase(Locale.ROOT), size, Timestamp.from(now));
        var signed = storage.signUpload(key, contentType, sha256.toLowerCase(Locale.ROOT), uploadTtl);
        return new Upload(id, signed.url().toString(), signed.requiredHeaders(), signed.expiresAt());
    }

    @Transactional
    public Media complete(UUID owner, UUID mediaId, int width, int height, Integer durationMs) {
        Media media = find(mediaId);
        if (!owner.equals(media.ownerId())) throw new VoiceMediaException("CHAT_MEDIA_FORBIDDEN");
        if (!chats.canAccessConversation(owner, media.conversationId())) {
            throw new VoiceMediaException("CONVERSATION_FORBIDDEN");
        }
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new VoiceMediaException("INVALID_CHAT_MEDIA_DIMENSIONS");
        }
        if ("Video".equals(media.kind())) {
            if (durationMs == null || durationMs < 1_000 || durationMs > MAX_VIDEO_DURATION_MS) {
                throw new VoiceMediaException("INVALID_CHAT_VIDEO_DURATION");
            }
        } else if (durationMs != null) {
            throw new VoiceMediaException("INVALID_CHAT_MEDIA_METADATA");
        }
        if ("READY".equals(media.status())) return media;
        var object = storage.head(media.objectKey());
        long maxBytes = "Image".equals(media.kind()) ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
        if (object.size() != media.sizeBytes() || object.size() > maxBytes
                || !media.contentType().equalsIgnoreCase(object.contentType())
                || object.sha256() == null || !object.sha256().equalsIgnoreCase(media.sha256())) {
            storage.delete(media.objectKey());
            throw new VoiceMediaException("CHAT_MEDIA_OBJECT_MISMATCH");
        }
        jdbc.update("""
                UPDATE chat_media
                SET width_px = ?, height_px = ?, duration_ms = ?, status = 'READY', completed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, width, height, durationMs, Timestamp.from(Instant.now()), bytes(mediaId));
        return new Media(media.id(), media.ownerId(), media.conversationId(), media.kind(),
                media.objectKey(), media.contentType(), media.sha256(), media.sizeBytes(),
                width, height, durationMs, "READY");
    }

    public Media requireReady(UUID owner, UUID mediaId, String conversationId, String expectedKind) {
        Media media = find(mediaId);
        if (!owner.equals(media.ownerId()) || !conversationId.equals(media.conversationId())
                || !normalizeKind(expectedKind).equals(media.kind()) || !"READY".equals(media.status())) {
            throw new VoiceMediaException("CHAT_MEDIA_NOT_READY");
        }
        return media;
    }

    public Playback playback(UUID viewer, UUID mediaId) {
        Media media = find(mediaId);
        if (!"READY".equals(media.status()) || !chats.canAccessConversation(viewer, media.conversationId())) {
            throw new VoiceMediaException("CHAT_MEDIA_FORBIDDEN");
        }
        var signed = storage.signRead(media.objectKey(), readTtl);
        return new Playback(signed.url().toString(), signed.expiresAt());
    }

    private Media find(UUID id) {
        List<Media> rows = jdbc.query("""
                SELECT id, owner_user_id, conversation_id, media_kind, object_key, content_type,
                       sha256, size_bytes, width_px, height_px, duration_ms, status
                FROM chat_media WHERE id = ?
                """, (rs, row) -> new Media(uuid(rs.getBytes("id")), uuid(rs.getBytes("owner_user_id")),
                rs.getString("conversation_id"), rs.getString("media_kind"), rs.getString("object_key"),
                rs.getString("content_type"), rs.getString("sha256"), rs.getLong("size_bytes"),
                (Integer) rs.getObject("width_px"), (Integer) rs.getObject("height_px"),
                (Integer) rs.getObject("duration_ms"), rs.getString("status")), bytes(id));
        if (rows.isEmpty()) throw new VoiceMediaException("CHAT_MEDIA_NOT_FOUND");
        return rows.get(0);
    }

    private static String normalizeKind(String value) {
        if (value == null) throw new VoiceMediaException("INVALID_CHAT_MEDIA_KIND");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "image" -> "Image";
            case "video" -> "Video";
            default -> throw new VoiceMediaException("INVALID_CHAT_MEDIA_KIND");
        };
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Upload(UUID mediaId, String uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record Playback(String playbackUrl, Instant expiresAt) {}
    public record Media(UUID id, UUID ownerId, String conversationId, String kind, String objectKey,
                        String contentType, String sha256, long sizeBytes, Integer width, Integer height,
                        Integer durationMs, String status) {}
}
