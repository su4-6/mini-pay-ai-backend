package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.AgentApplicationException;
import com.minipay.agent.application.service.AiConversationApplicationService;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/ai/conversations")
public class AiConversationController {
    private final AiConversationApplicationService conversations;

    public AiConversationController(AiConversationApplicationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(
            @Valid @RequestBody CreateConversationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return response(conversations.create(userId(jwt), request.title()));
    }

    @GetMapping
    public ConversationPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @AuthenticationPrincipal Jwt jwt) {
        ConversationCursor decoded = decode(cursor);
        List<AiConversation> items = conversations.list(
                userId(jwt), decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(), limit);
        String nextCursor = items.size() < limit || items.isEmpty()
                ? null
                : encode(new ConversationCursor(
                        items.getLast().lastMessageAt(), items.getLast().id()));
        return new ConversationPage(items.stream().map(AiConversationController::response).toList(), nextCursor);
    }

    @PatchMapping("/{conversationId}")
    public ConversationResponse rename(
            @PathVariable UUID conversationId,
            @Valid @RequestBody RenameConversationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return response(conversations.rename(userId(jwt), conversationId, request.title(), request.version()));
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID conversationId, @AuthenticationPrincipal Jwt jwt) {
        conversations.delete(userId(jwt), conversationId);
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePage messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<AiMessage> descending = conversations.listMessages(
                userId(jwt), conversationId, beforeSequence, limit);
        List<AiMessage> chronological = new java.util.ArrayList<>(descending);
        Collections.reverse(chronological);
        Long next = descending.size() < limit || descending.isEmpty()
                ? null : descending.getLast().sequenceNo();
        return new MessagePage(chronological.stream().map(AiConversationController::response).toList(), next);
    }

    private static ConversationResponse response(AiConversation value) {
        return new ConversationResponse(value.id(), value.title(), value.status(), value.version(),
                value.lastMessageAt(), value.createdAt(), value.updatedAt());
    }

    private static MessageResponse response(AiMessage value) {
        return new MessageResponse(value.id(), value.runId(), value.role().name(), value.contentText(),
                value.cardType(), value.cardVersion(), value.cardPayload(), value.sequenceNo(), value.createdAt());
    }

    private static UUID userId(Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        return UUID.fromString(claim == null ? jwt.getSubject() : claim);
    }

    private static String encode(ConversationCursor cursor) {
        String raw = cursor.time() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static ConversationCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            return new ConversationCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new AgentApplicationException("AGENT_CURSOR_INVALID", "会话游标无效");
        }
    }

    public record CreateConversationRequest(@Size(max = 128) String title) {
    }

    public record RenameConversationRequest(
            @NotBlank @Size(max = 128) String title,
            @NotNull @Min(0) Long version) {
    }

    public record ConversationResponse(
            UUID id, String title, String status, long version,
            Instant lastMessageAt, Instant createdAt, Instant updatedAt) {
    }

    public record ConversationPage(List<ConversationResponse> items, String nextCursor) {
    }

    public record MessageResponse(
            UUID id, UUID runId, String role, String content,
            String cardType, Integer cardVersion, String cardPayload,
            long sequenceNo, Instant createdAt) {
    }

    public record MessagePage(List<MessageResponse> items, Long nextCursor) {
    }

    private record ConversationCursor(Instant time, UUID id) {
    }
}
