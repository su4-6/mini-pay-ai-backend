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
public class GroupAvatarService {
    public static final String CONTENT_TYPE = "image/jpeg";
    public static final long MAX_BYTES = 5L * 1024L * 1024L;
    private final JdbcTemplate jdbc;
    private final ChatRepository chats;
    private final VoiceObjectStorage storage;
    private final Duration uploadTtl;
    private final Duration readTtl;

    public GroupAvatarService(JdbcTemplate jdbc, ChatRepository chats, VoiceObjectStorage storage,
            @Value("${minipay.agent.object-storage.upload-ttl:PT10M}") Duration uploadTtl,
            @Value("${minipay.agent.object-storage.read-ttl:PT5M}") Duration readTtl) {
        this.jdbc = jdbc; this.chats = chats; this.storage = storage;
        this.uploadTtl = uploadTtl; this.readTtl = readTtl;
    }

    @Transactional
    public Upload createUpload(UUID owner, String groupId, String contentType, long size, String sha256) {
        if (!chats.isGroupOwner(owner, groupId)) throw new VoiceMediaException("GROUP_OWNER_REQUIRED");
        if (!CONTENT_TYPE.equals(contentType) || size < 1 || size > MAX_BYTES
                || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new VoiceMediaException("INVALID_GROUP_AVATAR");
        }
        UUID id = UUID.randomUUID();
        String key = "agent/group-avatars/" + groupId + "/" + id + ".jpg";
        Instant now = Instant.now();
        Instant expires = now.plus(uploadTtl);
        jdbc.update("""
                INSERT INTO chat_group_avatar_upload
                    (id, group_id, owner_user_id, object_key, content_type, sha256,
                     size_bytes, status, created_at, expires_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL)
                """, bytes(id), groupId, bytes(owner), key, contentType,
                sha256.toLowerCase(), size, Timestamp.from(now), Timestamp.from(expires));
        var signed = storage.signUpload(key, contentType, sha256.toLowerCase(), uploadTtl);
        return new Upload(id, signed.url().toString(), signed.requiredHeaders(), signed.expiresAt());
    }

    @Transactional
    public Avatar complete(UUID owner, String groupId, UUID uploadId) {
        UploadRow upload = findUpload(uploadId);
        if (!upload.groupId().equals(groupId) || !upload.ownerId().equals(owner)
                || !chats.isGroupOwner(owner, groupId)) throw new VoiceMediaException("GROUP_OWNER_REQUIRED");
        if ("READY".equals(upload.status())) return avatar(groupId);
        if (upload.expiresAt().isBefore(Instant.now())) throw new VoiceMediaException("GROUP_AVATAR_UPLOAD_EXPIRED");
        Integer newer = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_group_avatar_upload
                WHERE group_id = ? AND created_at > ?
                """, Integer.class, groupId, Timestamp.from(upload.createdAt()));
        if (newer != null && newer > 0) throw new VoiceMediaException("GROUP_AVATAR_UPLOAD_SUPERSEDED");
        var object = storage.head(upload.objectKey());
        if (object.size() != upload.sizeBytes() || object.size() > MAX_BYTES
                || !CONTENT_TYPE.equals(object.contentType()) || object.sha256() == null
                || !object.sha256().equalsIgnoreCase(upload.sha256())) {
            storage.delete(upload.objectKey());
            throw new VoiceMediaException("GROUP_AVATAR_OBJECT_MISMATCH");
        }
        String previous = jdbc.queryForObject("SELECT avatar_object_key FROM chat_group WHERE id = ?", String.class, groupId);
        Instant now = Instant.now();
        jdbc.update("UPDATE chat_group SET avatar_object_key = ?, avatar_updated_at = ? WHERE id = ? AND owner_id = ?",
                upload.objectKey(), Timestamp.from(now), groupId, bytes(owner));
        jdbc.update("UPDATE chat_group_avatar_upload SET status = 'READY', completed_at = ? WHERE id = ?",
                Timestamp.from(now), bytes(uploadId));
        if (previous != null && !previous.equals(upload.objectKey())) storage.delete(previous);
        return signed(upload.objectKey());
    }

    public Avatar avatar(String groupId) {
        List<String> keys = jdbc.query("SELECT avatar_object_key FROM chat_group WHERE id = ? AND avatar_object_key IS NOT NULL",
                (rs, row) -> rs.getString(1), groupId);
        return keys.isEmpty() ? null : signed(keys.get(0));
    }

    private Avatar signed(String key) {
        var read = storage.signRead(key, readTtl);
        return new Avatar(read.url().toString(), read.expiresAt());
    }

    private UploadRow findUpload(UUID id) {
        List<UploadRow> rows = jdbc.query("""
                SELECT id, group_id, owner_user_id, object_key, content_type, sha256,
                       size_bytes, status, created_at, expires_at
                FROM chat_group_avatar_upload WHERE id = ?
                """, (rs, row) -> new UploadRow(uuid(rs.getBytes("id")), rs.getString("group_id"),
                uuid(rs.getBytes("owner_user_id")), rs.getString("object_key"), rs.getString("content_type"),
                rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), bytes(id));
        if (rows.isEmpty()) throw new VoiceMediaException("GROUP_AVATAR_UPLOAD_NOT_FOUND");
        return rows.get(0);
    }

    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
    public record Upload(UUID uploadId, String uploadUrl, java.util.Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record Avatar(String avatarUrl, Instant avatarUrlExpiresAt) {}
    private record UploadRow(UUID id, String groupId, UUID ownerId, String objectKey, String contentType,
                             String sha256, long sizeBytes, String status, Instant createdAt, Instant expiresAt) {}
}
