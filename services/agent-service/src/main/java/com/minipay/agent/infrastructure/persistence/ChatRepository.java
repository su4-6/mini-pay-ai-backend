package com.minipay.agent.infrastructure.persistence;

import com.minipay.agent.domain.model.ChatConversation;
import com.minipay.agent.domain.model.ChatMessage;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChatRepository {
    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChatConversation> findConversations(UUID userId) {
        return jdbc.query("""
                SELECT c.id, c.user_id, c.contact_id, c.name, c.last_message, c.last_message_time,
                       COALESCE(u.unread_count, 0) AS unread_count, c.is_transfer,
                       c.avatar_color_index, c.created_at, c.updated_at
                FROM chat_conversation c
                LEFT JOIN chat_conversation_unread u
                  ON u.conversation_id = c.id AND u.user_id = ?
                WHERE (c.user_id = ? OR c.contact_id = ?)
                  AND (u.deleted_through_message_id IS NULL OR EXISTS (
                      SELECT 1 FROM chat_message visible_message
                      WHERE visible_message.conversation_id = c.id
                        AND visible_message.id > u.deleted_through_message_id
                  ))
                ORDER BY c.last_message_time DESC
                """,
                (rs, rowNum) -> mapConversation(rs),
                uuidToBytes(userId), uuidToBytes(userId), userId.toString());
    }

    public List<ChatConversation> findGroupConversations(UUID userId) {
        return jdbc.query("""
                SELECT g.id, g.name, COALESCE(m.content, '') AS last_message,
                       COALESCE(UNIX_TIMESTAMP(m.created_at) * 1000, 0) AS last_message_time,
                       COALESCE(u.unread_count, 0) AS unread_count, g.created_at
                FROM chat_group g JOIN chat_group_member gm ON gm.group_id = g.id
                LEFT JOIN chat_conversation_unread u
                  ON u.conversation_id = g.id AND u.user_id = ?
                LEFT JOIN chat_message m ON m.id = (
                    SELECT newest.id FROM chat_message newest
                    WHERE newest.conversation_id = g.id ORDER BY newest.created_at DESC LIMIT 1)
                WHERE gm.user_id = ?
                  AND (u.deleted_through_message_id IS NULL OR EXISTS (
                      SELECT 1 FROM chat_message visible_message
                      WHERE visible_message.conversation_id = g.id
                        AND visible_message.id > u.deleted_through_message_id
                  ))
                ORDER BY last_message_time DESC
        """, (rs, rowNum) -> new ChatConversation(rs.getString("id"), userId, "",
                rs.getString("name"), rs.getString("last_message"), rs.getLong("last_message_time"),
                rs.getInt("unread_count"), false, Math.abs(rs.getString("id").hashCode() % 8),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
                uuidToBytes(userId), uuidToBytes(userId));
    }

    @Transactional
    public ChatConversation createGroup(UUID creatorId, List<GroupMember> memberInputs, String name) {
        String id = "group_" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO chat_group (id, name, owner_id, created_at) VALUES (?, ?, ?, ?)", id, name, uuidToBytes(creatorId), Timestamp.from(now));
        java.util.LinkedHashMap<UUID, String> members = new java.util.LinkedHashMap<>();
        for (GroupMember member : memberInputs) members.put(member.userId(), member.originalNickname());
        members.putIfAbsent(creatorId, null);
        for (var member : members.entrySet()) {
            jdbc.update("INSERT INTO chat_group_member (group_id, user_id, original_nickname, joined_at) VALUES (?, ?, ?, ?)",
                    id, uuidToBytes(member.getKey()), member.getValue(), Timestamp.from(now));
            ensureUnreadState(id, member.getKey(), now);
        }
        return new ChatConversation(id, creatorId, "", name, "", 0, 0, false,
                Math.abs(id.hashCode() % 8), now, now);
    }

    public Optional<GroupDetail> findGroupDetail(UUID userId, String groupId) {
        if (!isGroupMember(userId, groupId)) return Optional.empty();
        List<GroupDetail> groups = jdbc.query("SELECT id, name, owner_id, avatar_object_key FROM chat_group WHERE id = ?", (rs, rowNum) ->
                new GroupDetail(rs.getString("id"), rs.getString("name"), bytesToUuid(rs.getBytes("owner_id")),
                        rs.getString("avatar_object_key"), findGroupMembers(rs.getString("id"))), groupId);
        return groups.isEmpty() ? Optional.empty() : Optional.of(groups.get(0));
    }

    public List<GroupMember> findGroupMembers(String groupId) {
        return jdbc.query("SELECT user_id, nickname, original_nickname FROM chat_group_member WHERE group_id = ? ORDER BY joined_at", (rs, rowNum) ->
                new GroupMember(bytesToUuid(rs.getBytes("user_id")), rs.getString("nickname"), rs.getString("original_nickname")), groupId);
    }

    public Optional<GroupMember> findGroupMember(String groupId, UUID userId) {
        List<GroupMember> members = jdbc.query(
                "SELECT user_id, nickname, original_nickname FROM chat_group_member WHERE group_id = ? AND user_id = ?",
                (rs, rowNum) -> new GroupMember(bytesToUuid(rs.getBytes("user_id")), rs.getString("nickname"), rs.getString("original_nickname")),
                groupId, uuidToBytes(userId));
        return members.isEmpty() ? Optional.empty() : Optional.of(members.get(0));
    }

    @Transactional
    public boolean addGroupMembers(UUID actorId, String groupId, List<GroupMember> memberInputs) {
        if (!isGroupOwner(actorId, groupId)) return false;
        Instant now = Instant.now();
        for (GroupMember member : memberInputs) {
            jdbc.update("INSERT IGNORE INTO chat_group_member (group_id, user_id, original_nickname, joined_at) VALUES (?, ?, ?, ?)", groupId, uuidToBytes(member.userId()), member.originalNickname(), Timestamp.from(now));
            ensureUnreadState(groupId, member.userId(), now);
        }
        return true;
    }

    @Transactional
    public boolean removeGroupMember(UUID actorId, String groupId, UUID memberId) {
        if (!isGroupOwner(actorId, groupId) || actorId.equals(memberId)) return false;
        boolean removed = jdbc.update("DELETE FROM chat_group_member WHERE group_id = ? AND user_id = ?", groupId, uuidToBytes(memberId)) > 0;
        if (removed) jdbc.update("DELETE FROM chat_conversation_unread WHERE conversation_id = ? AND user_id = ?", groupId, uuidToBytes(memberId));
        return removed;
    }

    public boolean renameGroup(UUID actorId, String groupId, String name) {
        if (!isGroupOwner(actorId, groupId)) return false;
        return jdbc.update("UPDATE chat_group SET name = ? WHERE id = ?", name, groupId) > 0;
    }

    public boolean updateMyGroupNickname(UUID actorId, String groupId, String nickname) {
        return jdbc.update("UPDATE chat_group_member SET nickname = ? WHERE group_id = ? AND user_id = ?", nickname, groupId, uuidToBytes(actorId)) > 0;
    }

    @Transactional
    public boolean disbandGroup(UUID actorId, String groupId) {
        if (!isGroupOwner(actorId, groupId)) return false;
        jdbc.update("DELETE FROM chat_conversation_unread WHERE conversation_id = ?", groupId);
        jdbc.update("DELETE FROM chat_group_member WHERE group_id = ?", groupId);
        return jdbc.update("DELETE FROM chat_group WHERE id = ?", groupId) > 0;
    }

    @Transactional
    public boolean leaveGroup(UUID actorId, String groupId) {
        if (isGroupOwner(actorId, groupId)) return false;
        boolean removed = jdbc.update("DELETE FROM chat_group_member WHERE group_id = ? AND user_id = ?", groupId, uuidToBytes(actorId)) > 0;
        if (removed) jdbc.update("DELETE FROM chat_conversation_unread WHERE conversation_id = ? AND user_id = ?", groupId, uuidToBytes(actorId));
        return removed;
    }

    public boolean isGroupMember(UUID userId, String groupId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_group_member WHERE group_id = ? AND user_id = ?", Integer.class, groupId, uuidToBytes(userId));
        return count != null && count > 0;
    }

    public boolean isGroupOwner(UUID userId, String groupId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_group WHERE id = ? AND owner_id = ?", Integer.class, groupId, uuidToBytes(userId));
        return count != null && count > 0;
    }

    public record GroupDetail(String id, String name, UUID ownerId, String avatarObjectKey, List<GroupMember> members) {}
    public record GroupMember(UUID userId, String nickname, String originalNickname) {}

    public boolean canAccessConversation(UUID userId, String conversationId) {
        Integer direct = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_conversation WHERE id = ? AND (user_id = ? OR contact_id = ?)",
                Integer.class, conversationId, uuidToBytes(userId), userId.toString());
        if (direct != null && direct > 0) return true;
        Integer group = jdbc.queryForObject("SELECT COUNT(*) FROM chat_group_member WHERE group_id = ? AND user_id = ?",
                Integer.class, conversationId, uuidToBytes(userId));
        return group != null && group > 0;
    }

    public Optional<UUID> findDirectPeer(UUID userId, String conversationId) {
        List<UUID> peers = jdbc.query("""
                SELECT user_id, contact_id FROM chat_conversation
                WHERE id = ? AND (user_id = ? OR contact_id = ?)
                """, (rs, row) -> {
            UUID owner = bytesToUuid(rs.getBytes("user_id"));
            UUID contact = UUID.fromString(rs.getString("contact_id"));
            return userId.equals(owner) ? contact : owner;
        }, conversationId, uuidToBytes(userId), userId.toString());
        return peers.isEmpty() ? Optional.empty() : Optional.of(peers.get(0));
    }

    public List<UUID> findConversationParticipants(String conversationId) {
        List<UUID> direct = jdbc.query("SELECT user_id, contact_id FROM chat_conversation WHERE id = ?", (rs, row) ->
                List.of(bytesToUuid(rs.getBytes("user_id")), UUID.fromString(rs.getString("contact_id"))), conversationId)
                .stream().flatMap(List::stream).toList();
        if (!direct.isEmpty()) return direct;
        return jdbc.query("SELECT user_id FROM chat_group_member WHERE group_id = ?", (rs, row) -> bytesToUuid(rs.getBytes("user_id")), conversationId);
    }

    public Optional<ChatConversation> findConversation(UUID userId, String conversationId) {
        List<ChatConversation> results = jdbc.query("""
                SELECT c.id, c.user_id, c.contact_id, c.name, c.last_message, c.last_message_time,
                       COALESCE(u.unread_count, 0) AS unread_count, c.is_transfer,
                       c.avatar_color_index, c.created_at, c.updated_at
                FROM chat_conversation c
                LEFT JOIN chat_conversation_unread u
                  ON u.conversation_id = c.id AND u.user_id = ?
                WHERE c.id = ?
                """,
                (rs, rowNum) -> mapConversation(rs),
                uuidToBytes(userId), conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ChatMessage> findMessages(UUID userId, String conversationId, int limit, int offset) {
        return jdbc.query("""
                SELECT id, conversation_id, sender_id, sender_type, content,
                       message_type, transfer_amount, transfer_status, transfer_direction,
                       transfer_id, transfer_target_user_id,
                       voice_media_id, voice_duration_ms, media_id, media_kind,
                       media_content_type, media_width_px, media_height_px, media_duration_ms,
                       call_id, call_status,
                       call_duration_seconds, created_at
                FROM chat_message
                WHERE conversation_id = ?
                  AND id > COALESCE((
                      SELECT deleted_through_message_id
                      FROM chat_conversation_unread
                      WHERE conversation_id = ? AND user_id = ?
                  ), 0)
                ORDER BY created_at ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> mapMessage(rs),
                conversationId, conversationId, uuidToBytes(userId), limit, offset);
    }

    public int countMessages(UUID userId, String conversationId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM chat_message
                WHERE conversation_id = ?
                  AND id > COALESCE((
                      SELECT deleted_through_message_id
                      FROM chat_conversation_unread
                      WHERE conversation_id = ? AND user_id = ?
                  ), 0)
                """,
                Integer.class, conversationId, conversationId, uuidToBytes(userId));
        return count == null ? 0 : count;
    }

    @Transactional
    public void deleteConversationForUser(UUID userId, String conversationId) {
        Long latestMessageId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE conversation_id = ?",
                Long.class, conversationId);
        long deletedThrough = latestMessageId == null ? 0L : latestMessageId;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, deleted_through_message_id,
                    created_at, updated_at
                ) VALUES (?, ?, 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    unread_count = 0,
                    deleted_through_message_id = GREATEST(
                        COALESCE(deleted_through_message_id, 0),
                        VALUES(deleted_through_message_id)
                    ),
                    updated_at = VALUES(updated_at)
                """, conversationId, uuidToBytes(userId), deletedThrough,
                Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional
    public void upsertConversation(ChatConversation conv) {
        jdbc.update("""
                INSERT INTO chat_conversation (
                    id, user_id, contact_id, name, last_message, last_message_time,
                    unread_count, is_transfer, avatar_color_index, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    last_message = VALUES(last_message),
                    last_message_time = VALUES(last_message_time),
                    unread_count = VALUES(unread_count),
                    is_transfer = VALUES(is_transfer),
                    avatar_color_index = VALUES(avatar_color_index),
                    updated_at = VALUES(updated_at)
                """,
                conv.id(), uuidToBytes(conv.userId()), conv.contactId(), conv.name(),
                conv.lastMessage(), conv.lastMessageTime(), conv.unreadCount(),
                conv.isTransfer() ? 1 : 0, conv.avatarColorIndex(),
                Timestamp.from(conv.createdAt()), Timestamp.from(conv.updatedAt()));
    }

    @Transactional
    public ChatMessage insertMessage(ChatMessage msg) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO chat_message (
                    conversation_id, sender_id, sender_type, content, message_type,
                    transfer_amount, transfer_status, transfer_direction, transfer_id,
                    transfer_target_user_id, voice_media_id,
                    voice_duration_ms, media_id, media_kind, media_content_type,
                    media_width_px, media_height_px, media_duration_ms,
                    call_id, call_status, call_duration_seconds, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                msg.conversationId(), uuidToBytes(msg.senderId()), msg.senderType(),
                msg.content(), msg.messageType(), msg.transferAmount(),
                msg.transferStatus(), msg.transferDirection(),
                msg.transferId() == null ? null : uuidToBytes(msg.transferId()),
                msg.transferTargetUserId() == null ? null : uuidToBytes(msg.transferTargetUserId()),
                msg.voiceMediaId() == null ? null : uuidToBytes(msg.voiceMediaId()),
                msg.voiceDurationMs(), msg.mediaId() == null ? null : uuidToBytes(msg.mediaId()),
                msg.mediaKind(), msg.mediaContentType(), msg.mediaWidth(), msg.mediaHeight(),
                msg.mediaDurationMs(), msg.callId() == null ? null : uuidToBytes(msg.callId()),
                msg.callStatus(), msg.callDurationSeconds(),
                Timestamp.from(msg.createdAt()));

        if (inserted == 0 && msg.transferId() != null) {
            return findTransferMessage(msg.conversationId(), msg.transferId())
                    .orElseThrow(() -> new IllegalStateException("Transfer message conflict without existing row"));
        }

        Long generatedId = jdbc.queryForObject(
                "SELECT LAST_INSERT_ID()", Long.class);
        long id = generatedId == null ? 0L : generatedId;
        jdbc.update("""
                UPDATE chat_conversation
                SET last_message = ?, last_message_time = ?, is_transfer = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                msg.content(), msg.createdAt().toEpochMilli(),
                "Transfer".equals(msg.messageType()) ? 1 : 0,
                Timestamp.from(msg.createdAt()), msg.conversationId());
        ensureParticipantUnreadStates(msg.conversationId(), msg.createdAt());
        jdbc.update("""
                UPDATE chat_conversation_unread
                SET unread_count = unread_count + 1, updated_at = ?
                WHERE conversation_id = ? AND user_id <> ?
                """, Timestamp.from(msg.createdAt()), msg.conversationId(), uuidToBytes(msg.senderId()));
        return new ChatMessage(id, msg.conversationId(), msg.senderId(),
                msg.senderType(), msg.content(), msg.messageType(),
                msg.transferAmount(), msg.transferStatus(),
                msg.transferDirection(), msg.transferId(), msg.transferTargetUserId(),
                msg.voiceMediaId(), msg.voiceDurationMs(),
                msg.mediaId(), msg.mediaKind(), msg.mediaContentType(), msg.mediaWidth(),
                msg.mediaHeight(), msg.mediaDurationMs(),
                msg.callId(), msg.callStatus(), msg.callDurationSeconds(), msg.createdAt());
    }

    public Optional<ChatMessage> findTransferMessage(String conversationId, UUID transferId) {
        List<ChatMessage> rows = jdbc.query("""
                SELECT id, conversation_id, sender_id, sender_type, content,
                       message_type, transfer_amount, transfer_status, transfer_direction,
                       transfer_id, transfer_target_user_id, voice_media_id, voice_duration_ms,
                       media_id, media_kind, media_content_type, media_width_px, media_height_px, media_duration_ms,
                       call_id, call_status, call_duration_seconds, created_at
                FROM chat_message WHERE conversation_id = ? AND transfer_id = ?
                """, (rs, row) -> mapMessage(rs), conversationId, uuidToBytes(transferId));
        return rows.stream().findFirst();
    }

    public void clearUnread(UUID userId, String conversationId) {
        jdbc.update("""
                INSERT INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, created_at, updated_at
                ) VALUES (?, ?, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE unread_count = 0, updated_at = CURRENT_TIMESTAMP(6)
                """, conversationId, uuidToBytes(userId));
    }

    @Transactional
    public void ensureConversationExists(
            UUID userId, String conversationId, String contactId,
            String name, int avatarColorIndex) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_conversation WHERE id = ?",
                Integer.class, conversationId);
        if (count != null && count == 0) {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO chat_conversation (
                        id, user_id, contact_id, name, last_message, last_message_time,
                        unread_count, is_transfer, avatar_color_index, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, '', 0, 0, 0, ?, ?, ?)
                    """,
                    conversationId, uuidToBytes(userId), contactId, name,
                    avatarColorIndex, Timestamp.from(now), Timestamp.from(now));
        }
        Instant now = Instant.now();
        ensureUnreadState(conversationId, userId, now);
        ensureUnreadState(conversationId, UUID.fromString(contactId), now);
    }

    private void ensureParticipantUnreadStates(String conversationId, Instant now) {
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, created_at, updated_at
                )
                SELECT id, user_id, 0, ?, ? FROM chat_conversation WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), conversationId);
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, created_at, updated_at
                )
                SELECT id, UNHEX(REPLACE(contact_id, '-', '')), 0, ?, ?
                FROM chat_conversation
                WHERE id = ?
                  AND contact_id REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                """, Timestamp.from(now), Timestamp.from(now), conversationId);
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, created_at, updated_at
                )
                SELECT group_id, user_id, 0, ?, ? FROM chat_group_member WHERE group_id = ?
                """, Timestamp.from(now), Timestamp.from(now), conversationId);
    }

    private void ensureUnreadState(String conversationId, UUID userId, Instant now) {
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_unread (
                    conversation_id, user_id, unread_count, created_at, updated_at
                ) VALUES (?, ?, 0, ?, ?)
                """, conversationId, uuidToBytes(userId), Timestamp.from(now), Timestamp.from(now));
    }

    private static ChatConversation mapConversation(ResultSet rs) throws SQLException {
        return new ChatConversation(
                rs.getString("id"),
                bytesToUuid(rs.getBytes("user_id")),
                rs.getString("contact_id"),
                rs.getString("name"),
                rs.getString("last_message"),
                rs.getLong("last_message_time"),
                rs.getInt("unread_count"),
                rs.getInt("is_transfer") == 1,
                rs.getInt("avatar_color_index"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ChatMessage mapMessage(ResultSet rs) throws SQLException {
        Timestamp ca = rs.getTimestamp("created_at");
        return new ChatMessage(
                rs.getLong("id"),
                rs.getString("conversation_id"),
                bytesToUuid(rs.getBytes("sender_id")),
                rs.getString("sender_type"),
                rs.getString("content"),
                rs.getString("message_type"),
                rs.getString("transfer_amount"),
                rs.getString("transfer_status"),
                rs.getString("transfer_direction"),
                bytesToUuid(rs.getBytes("transfer_id")),
                bytesToUuid(rs.getBytes("transfer_target_user_id")),
                bytesToUuid(rs.getBytes("voice_media_id")),
                (Integer) rs.getObject("voice_duration_ms"),
                bytesToUuid(rs.getBytes("media_id")),
                rs.getString("media_kind"),
                rs.getString("media_content_type"),
                (Integer) rs.getObject("media_width_px"),
                (Integer) rs.getObject("media_height_px"),
                (Integer) rs.getObject("media_duration_ms"),
                bytesToUuid(rs.getBytes("call_id")),
                rs.getString("call_status"),
                (Integer) rs.getObject("call_duration_seconds"),
                ca == null ? null : ca.toInstant());
    }

    static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    static UUID bytesToUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
