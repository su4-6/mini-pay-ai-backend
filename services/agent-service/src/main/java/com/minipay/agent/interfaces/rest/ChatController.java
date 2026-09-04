package com.minipay.agent.interfaces.rest;

import com.minipay.agent.domain.model.ChatConversation;
import com.minipay.agent.domain.model.ChatMessage;
import com.minipay.agent.application.service.VoiceMediaException;
import com.minipay.agent.application.service.VoiceMediaService;
import com.minipay.agent.application.service.ChatMediaService;
import com.minipay.agent.application.service.GroupAvatarService;
import com.minipay.agent.infrastructure.client.PaymentTransferVerificationClient;
import com.minipay.agent.infrastructure.client.IdentityProfileClient;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import com.minipay.agent.infrastructure.realtime.RealtimeSessionRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/conversations")
public class ChatController {
    private final ChatRepository chatRepository;
    private final VoiceMediaService voiceMediaService;
    private final ChatMediaService chatMediaService;
    private final RealtimeSessionRegistry realtime;
    private final PaymentTransferVerificationClient transfers;
    private final GroupAvatarService groupAvatars;
    private final IdentityProfileClient identityProfiles;

    public ChatController(ChatRepository chatRepository, VoiceMediaService voiceMediaService,
            ChatMediaService chatMediaService,
            RealtimeSessionRegistry realtime, PaymentTransferVerificationClient transfers,
            GroupAvatarService groupAvatars, IdentityProfileClient identityProfiles) {
        this.chatRepository = chatRepository;
        this.voiceMediaService = voiceMediaService;
        this.chatMediaService = chatMediaService;
        this.realtime = realtime;
        this.transfers = transfers;
        this.groupAvatars = groupAvatars;
        this.identityProfiles = identityProfiles;
    }

    @GetMapping
    public List<ConversationResponse> listConversations(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<ChatConversation> direct = chatRepository.findConversations(userId);
        Map<UUID, IdentityProfileClient.Profile> profiles = identityProfiles.findProfiles(
                direct.stream().map(conversation -> chatRepository.findDirectPeer(userId, conversation.id()).orElse(null)).toList());
        return java.util.stream.Stream.concat(direct.stream(), chatRepository.findGroupConversations(userId).stream())
                .sorted(java.util.Comparator.comparingLong(ChatConversation::lastMessageTime).reversed())
                .map(conversation -> toConversationResponse(conversation, userId, profiles))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(
            @Valid @RequestBody CreateConversationRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID contactId = UUID.fromString(body.contactId());
        String conversationId = conversationId(userId, contactId);
        int avatarColorIndex = Math.abs(contactId.hashCode() % 8);

        chatRepository.ensureConversationExists(
                userId, conversationId, body.contactId(), body.name(), avatarColorIndex);

        ChatConversation conv = chatRepository.findConversation(userId, conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation not found after create"));
        return toConversationResponse(conv, userId,
                identityProfiles.findProfiles(List.of(contactId)));
    }

    @GetMapping("/{conversationId}/messages")
    public MessageListResponse listMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!chatRepository.canAccessConversation(userId, conversationId)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        List<ChatMessage> messages = chatRepository.findMessages(
                userId, conversationId, limit, offset);
        int total = chatRepository.countMessages(userId, conversationId);
        // Clear unread for the current user's view
        chatRepository.clearUnread(userId, conversationId);
        Map<UUID, IdentityProfileClient.Profile> profiles = identityProfiles.findProfiles(
                messages.stream()
                        .flatMap(message -> java.util.stream.Stream.of(message.senderId(), message.transferTargetUserId()))
                        .filter(java.util.Objects::nonNull)
                        .toList());
        return new MessageListResponse(
                messages.stream()
                        .map(m -> toMessageResponse(m, userId, profiles))
                        .toList(),
                total);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @PathVariable String conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!chatRepository.canAccessConversation(userId, conversationId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        chatRepository.deleteConversationForUser(userId, conversationId);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse sendMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!chatRepository.canAccessConversation(userId, conversationId)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        ValidatedMessage validated = validateMessage(body, userId, conversationId, jwt.getTokenValue());
        TransferData transfer = validated.transfer();
        ChatMediaService.Media media = validated.media();
        ChatMessage msg = chatRepository.insertMessage(new ChatMessage(
                null,
                conversationId,
                userId,
                getSenderType(body.contactId()), // "Me" or "Other" derived at server
                transfer == null ? body.content() : transfer.content(),
                body.messageType(),
                transfer == null ? body.transferAmount() : transfer.amount(),
                transfer == null ? body.transferStatus() : "SUCCEEDED",
                transfer == null ? body.transferDirection() : "Outgoing",
                transfer == null ? null : transfer.transferId(),
                transfer == null ? null : transfer.targetUserId(),
                body.voiceMediaId(),
                body.voiceDurationMs(),
                media == null ? null : media.id(),
                media == null ? null : media.kind(),
                media == null ? null : media.contentType(),
                media == null ? null : media.width(),
                media == null ? null : media.height(),
                media == null ? null : media.durationMs(),
                null,
                null,
                null,
                Instant.now()));
        var event = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.Map.of("messageId", msg.id()));
        chatRepository.findConversationParticipants(conversationId).stream().filter(participant -> !participant.equals(userId))
                .forEach(participant -> realtime.publish(participant, "CHAT_MESSAGE_CREATED", null, conversationId, event));
        return toMessageResponse(msg, userId,
                identityProfiles.findProfiles(java.util.stream.Stream.of(msg.senderId(), msg.transferTargetUserId())
                        .filter(java.util.Objects::nonNull).toList()));
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createGroup(@Valid @RequestBody CreateGroupRequest body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<ChatRepository.GroupMember> members = body.members().stream()
                .map(member -> new ChatRepository.GroupMember(UUID.fromString(member.userId()), null, member.nickname())).toList();
        return toConversationResponse(chatRepository.createGroup(userId, members, body.name()), userId, Map.of());
    }

    @GetMapping("/groups/{groupId}")
    public GroupDetailResponse groupDetail(@PathVariable String groupId, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return chatRepository.findGroupDetail(userId, groupId)
                .map(detail -> toGroupDetailResponse(detail, identityProfiles.findProfiles(
                        detail.members().stream().map(ChatRepository.GroupMember::userId).toList())))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @PostMapping("/groups/{groupId}/avatar-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupAvatarUploadResponse createGroupAvatarUpload(
            @PathVariable String groupId, @Valid @RequestBody GroupAvatarUploadRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            var upload = groupAvatars.createUpload(UUID.fromString(jwt.getSubject()), groupId,
                    body.contentType(), body.sizeBytes(), body.sha256());
            return new GroupAvatarUploadResponse(upload.uploadId(), upload.uploadUrl(),
                    upload.requiredHeaders(), upload.expiresAt());
        } catch (VoiceMediaException error) {
            throw new org.springframework.web.server.ResponseStatusException(
                    "GROUP_OWNER_REQUIRED".equals(error.code()) ? HttpStatus.FORBIDDEN : HttpStatus.UNPROCESSABLE_ENTITY,
                    error.code(), error);
        }
    }

    @PostMapping("/groups/{groupId}/avatar-uploads/{uploadId}/complete")
    public GroupAvatarResponse completeGroupAvatarUpload(
            @PathVariable String groupId, @PathVariable UUID uploadId,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            var avatar = groupAvatars.complete(UUID.fromString(jwt.getSubject()), groupId, uploadId);
            return new GroupAvatarResponse(avatar.avatarUrl(), avatar.avatarUrlExpiresAt());
        } catch (VoiceMediaException error) {
            throw new org.springframework.web.server.ResponseStatusException(
                    "GROUP_OWNER_REQUIRED".equals(error.code()) ? HttpStatus.FORBIDDEN : HttpStatus.UNPROCESSABLE_ENTITY,
                    error.code(), error);
        }
    }

    @PostMapping("/groups/{groupId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addGroupMembers(@PathVariable String groupId, @Valid @RequestBody GroupMembersRequest body, @AuthenticationPrincipal Jwt jwt) {
        boolean changed = chatRepository.addGroupMembers(UUID.fromString(jwt.getSubject()), groupId, body.members().stream()
                .map(member -> new ChatRepository.GroupMember(UUID.fromString(member.userId()), null, member.nickname())).toList());
        if (!changed) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGroupMember(@PathVariable String groupId, @PathVariable String memberId, @AuthenticationPrincipal Jwt jwt) {
        if (!chatRepository.removeGroupMember(UUID.fromString(jwt.getSubject()), groupId, UUID.fromString(memberId))) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @PutMapping("/groups/{groupId}/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renameGroup(@PathVariable String groupId, @Valid @RequestBody TextRequest body, @AuthenticationPrincipal Jwt jwt) {
        if (!chatRepository.renameGroup(UUID.fromString(jwt.getSubject()), groupId, body.value())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @PutMapping("/groups/{groupId}/my-nickname")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMyNickname(@PathVariable String groupId, @Valid @RequestBody TextRequest body, @AuthenticationPrincipal Jwt jwt) {
        if (!chatRepository.updateMyGroupNickname(UUID.fromString(jwt.getSubject()), groupId, body.value())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @DeleteMapping("/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disbandGroup(@PathVariable String groupId, @AuthenticationPrincipal Jwt jwt) {
        if (!chatRepository.disbandGroup(UUID.fromString(jwt.getSubject()), groupId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @DeleteMapping("/groups/{groupId}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveGroup(@PathVariable String groupId, @AuthenticationPrincipal Jwt jwt) {
        if (!chatRepository.leaveGroup(UUID.fromString(jwt.getSubject()), groupId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static String getSenderType(String contactId) {
        // If contactId is provided, the sender is the current user (Me).
        // The server always treats the authenticated user as the sender.
        return "Me";
    }

    private ValidatedMessage validateMessage(SendMessageRequest body, UUID userId, String conversationId, String token) {
        if ("Voice".equals(body.messageType())) {
            if (body.voiceMediaId() == null || body.voiceDurationMs() == null) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VOICE_MEDIA_REQUIRED");
            }
            try {
                voiceMediaService.requireReady(userId, body.voiceMediaId(), conversationId, body.voiceDurationMs());
            } catch (VoiceMediaException e) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e);
            }
            return new ValidatedMessage(null, null);
        } else if ("Image".equals(body.messageType()) || "Video".equals(body.messageType())) {
            if (body.mediaId() == null) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CHAT_MEDIA_REQUIRED");
            }
            try {
                return new ValidatedMessage(null,
                        chatMediaService.requireReady(userId, body.mediaId(), conversationId, body.messageType()));
            } catch (VoiceMediaException error) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        error.code(), error);
            }
        } else if ("Transfer".equals(body.messageType())) {
            if (body.transferId() == null || body.transferTargetUserId() == null
                    || userId.equals(body.transferTargetUserId())) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER");
            }
            boolean group = conversationId.startsWith("group_");
            if (group && !chatRepository.isGroupMember(body.transferTargetUserId(), conversationId)) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GROUP_TRANSFER");
            }
            if (!group && !chatRepository.findDirectPeer(userId, conversationId)
                    .filter(body.transferTargetUserId()::equals).isPresent()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECT_TRANSFER");
            }
            try {
                var receipt = transfers.requireSucceeded(token, body.transferId());
                if (!body.transferTargetUserId().equals(receipt.receiverUserId())) {
                    throw new IllegalArgumentException("TRANSFER_RECIPIENT_MISMATCH");
                }
                String targetName;
                if (group) {
                    var target = chatRepository.findGroupMember(conversationId, body.transferTargetUserId()).orElseThrow();
                    targetName = target.nickname() == null || target.nickname().isBlank()
                            ? target.originalNickname() : target.nickname();
                    if (targetName == null || targetName.isBlank()) targetName = "群成员";
                } else {
                    IdentityProfileClient.Profile target = identityProfiles
                            .findProfiles(List.of(body.transferTargetUserId())).get(body.transferTargetUserId());
                    targetName = target == null || target.nickname() == null || target.nickname().isBlank()
                            ? "好友" : target.nickname();
                }
                return new ValidatedMessage(new TransferData(body.transferId(), body.transferTargetUserId(),
                        java.math.BigDecimal.valueOf(receipt.amountCent(), 2).toPlainString(),
                        "转账给" + targetName), null);
            } catch (RuntimeException error) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        error.getMessage() == null ? "TRANSFER_VERIFICATION_FAILED" : error.getMessage(), error);
            }
        } else if (!"Text".equals(body.messageType())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_MESSAGE_TYPE");
        }
        return new ValidatedMessage(null, null);
    }

    private static String conversationId(UUID userA, UUID userB) {
        // Deterministic conversation ID from sorted user UUIDs
        UUID first = userA.compareTo(userB) < 0 ? userA : userB;
        UUID second = userA.compareTo(userB) < 0 ? userB : userA;
        String input = first + ":" + second;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return "conv_" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private ConversationResponse toConversationResponse(ChatConversation c, UUID currentUserId,
            Map<UUID, IdentityProfileClient.Profile> profiles) {
        GroupAvatarService.Avatar avatar = c.id().startsWith("group_") ? safeAvatar(c.id()) : null;
        if (avatar == null && !c.id().startsWith("group_")) {
            IdentityProfileClient.Profile profile = chatRepository.findDirectPeer(currentUserId, c.id())
                    .map(profiles::get).orElse(null);
            if (profile != null) {
                return new ConversationResponse(c.id(), c.name(), c.lastMessage(), c.lastMessageTime(),
                        c.unreadCount(), c.isTransfer(), c.avatarColorIndex(),
                        profile.avatarUrl(), profile.avatarUrlExpiresAt());
            }
        }
        return new ConversationResponse(
                c.id(), c.name(), c.lastMessage(), c.lastMessageTime(),
                c.unreadCount(), c.isTransfer(), c.avatarColorIndex(),
                avatar == null ? null : avatar.avatarUrl(),
                avatar == null ? null : avatar.avatarUrlExpiresAt());
    }

    private GroupDetailResponse toGroupDetailResponse(ChatRepository.GroupDetail detail,
            Map<UUID, IdentityProfileClient.Profile> profiles) {
        GroupAvatarService.Avatar avatar = safeAvatar(detail.id());
        return new GroupDetailResponse(detail.id(), detail.name(), detail.ownerId().toString(),
                avatar == null ? null : avatar.avatarUrl(), avatar == null ? null : avatar.avatarUrlExpiresAt(), detail.members().stream()
                .map(member -> {
                    IdentityProfileClient.Profile profile = profiles.get(member.userId());
                    String latestName = profile != null && profile.nickname() != null && !profile.nickname().isBlank()
                            ? profile.nickname() : member.originalNickname();
                    return new GroupMemberResponse(member.userId().toString(), member.nickname(), latestName,
                            profile == null ? null : profile.avatarUrl(),
                            profile == null ? null : profile.avatarUrlExpiresAt());
                }).toList());
    }

    private GroupAvatarService.Avatar safeAvatar(String groupId) {
        try { return groupAvatars.avatar(groupId); }
        catch (RuntimeException ignored) { return null; }
    }

    private MessageResponse toMessageResponse(ChatMessage m, UUID currentUserId,
            Map<UUID, IdentityProfileClient.Profile> profiles) {
        String senderType = m.senderId().equals(currentUserId) ? "Me" : "Other";
        ChatRepository.GroupMember sender = m.conversationId().startsWith("group_")
                ? chatRepository.findGroupMember(m.conversationId(), m.senderId()).orElse(null)
                : null;
        IdentityProfileClient.Profile profile = profiles.get(m.senderId());
        String latestName = profile != null && profile.nickname() != null && !profile.nickname().isBlank()
                ? profile.nickname() : sender == null ? null : sender.originalNickname();
        String content = m.content();
        String transferDirection = m.transferDirection();
        if ("Transfer".equals(m.messageType()) && m.transferTargetUserId() != null) {
            boolean sentByCurrentUser = m.senderId().equals(currentUserId);
            transferDirection = sentByCurrentUser ? "Outgoing" : "Incoming";
            ChatRepository.GroupMember targetMember = m.conversationId().startsWith("group_")
                    ? chatRepository.findGroupMember(m.conversationId(), m.transferTargetUserId()).orElse(null)
                    : null;
            IdentityProfileClient.Profile targetProfile = profiles.get(m.transferTargetUserId());
            String senderName = sender != null && sender.nickname() != null && !sender.nickname().isBlank()
                    ? sender.nickname() : latestName;
            if (senderName == null || senderName.isBlank()) senderName = "好友";
            String targetName = targetMember != null && targetMember.nickname() != null && !targetMember.nickname().isBlank()
                    ? targetMember.nickname()
                    : targetProfile != null && targetProfile.nickname() != null && !targetProfile.nickname().isBlank()
                            ? targetProfile.nickname()
                            : targetMember != null && targetMember.originalNickname() != null
                                    ? targetMember.originalNickname() : "好友";
            if (m.conversationId().startsWith("group_")) {
                content = sentByCurrentUser ? "你向" + targetName + "转账" : senderName + "向" + targetName + "转账";
            } else {
                content = sentByCurrentUser ? "转账给" + targetName : senderName + "转账给你";
            }
        }
        return new MessageResponse(
                m.id(), m.conversationId(), senderType, m.senderId().toString(),
                sender == null ? null : sender.nickname(),
                latestName,
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.avatarUrlExpiresAt(), content,
                m.messageType(), m.transferAmount(), m.transferStatus(),
                transferDirection, m.voiceMediaId(), m.voiceDurationMs(),
                m.mediaId(), m.mediaKind(), m.mediaContentType(), m.mediaWidth(),
                m.mediaHeight(), m.mediaDurationMs(),
                m.transferId(), m.transferTargetUserId() == null ? null : m.transferTargetUserId().toString(),
                m.callId(), m.callStatus(), m.callDurationSeconds(), m.createdAt().toEpochMilli());
    }

    // --- DTOs ---

    public record ConversationResponse(
            String id,
            String name,
            String lastMessage,
            long lastMessageTime,
            int unreadCount,
            boolean isTransfer,
            int avatarColorIndex,
            String avatarUrl,
            Instant avatarUrlExpiresAt) {
    }

    public record MessageResponse(
            long id,
            String conversationId,
            String senderType,
            String senderId,
            String senderNickname,
            String senderOriginalNickname,
            String senderAvatarUrl,
            Instant senderAvatarUrlExpiresAt,
            String content,
            String messageType,
            String transferAmount,
            String transferStatus,
            String transferDirection,
            UUID voiceMediaId,
            Integer voiceDurationMs,
            UUID mediaId,
            String mediaKind,
            String mediaContentType,
            Integer mediaWidth,
            Integer mediaHeight,
            Integer mediaDurationMs,
            UUID transferId,
            String transferTargetUserId,
            UUID callId,
            String callStatus,
            Integer callDurationSeconds,
            long timestamp) {
    }

    public record MessageListResponse(
            List<MessageResponse> messages,
            int total) {
    }

    public record CreateConversationRequest(
            @NotBlank String contactId,
            @NotBlank @Size(max = 128) String name) {
    }

    public record GroupMemberInput(@NotBlank String userId, @NotBlank @Size(max = 128) String nickname) {}
    public record CreateGroupRequest(@NotBlank @Size(max = 128) String name, @Size(min = 1, max = 100) List<@Valid GroupMemberInput> members) {}

    public record GroupMembersRequest(@Size(min = 1, max = 100) List<@Valid GroupMemberInput> members) {}
    public record TextRequest(@NotBlank @Size(max = 128) String value) {}
    public record GroupMemberResponse(String userId, String nickname, String originalNickname,
                                      String avatarUrl, Instant avatarUrlExpiresAt) {}
    public record GroupDetailResponse(String id, String name, String ownerId, String avatarUrl,
                                      Instant avatarUrlExpiresAt, List<GroupMemberResponse> members) {}
    public record GroupAvatarUploadRequest(@NotBlank String contentType, long sizeBytes, @NotBlank String sha256) {}
    public record GroupAvatarUploadResponse(UUID uploadId, String uploadUrl,
                                            java.util.Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record GroupAvatarResponse(String avatarUrl, Instant avatarUrlExpiresAt) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 4096) String content,
            @NotBlank String messageType,
            String transferAmount,
            String transferStatus,
            String transferDirection,
            UUID transferId,
            UUID transferTargetUserId,
            UUID voiceMediaId,
            Integer voiceDurationMs,
            UUID mediaId,
            String contactId) {
    }
    private record TransferData(UUID transferId, UUID targetUserId, String amount, String content) {}
    private record ValidatedMessage(TransferData transfer, ChatMediaService.Media media) {}
}
