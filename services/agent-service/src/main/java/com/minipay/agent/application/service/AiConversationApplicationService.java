package com.minipay.agent.application.service;

import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiConversationApplicationService {
    private static final int MAX_PAGE_SIZE = 100;

    private final AiAgentRepository repository;
    private final Clock clock;

    @Autowired
    public AiConversationApplicationService(AiAgentRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AiConversationApplicationService(AiAgentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AiConversation create(UUID userId, String requestedTitle) {
        Instant now = clock.instant();
        String title = normalizeTitle(requestedTitle);
        AiConversation conversation = new AiConversation(
                UuidV7.generate(), userId, title, "ACTIVE", 0, 1,
                now, now, now, null);
        repository.insertConversation(conversation);
        return conversation;
    }

    @Transactional(readOnly = true)
    public AiConversation get(UUID userId, UUID conversationId) {
        return repository.findConversation(userId, conversationId)
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_CONVERSATION_NOT_FOUND", "AI 会话不存在或不可访问"));
    }

    @Transactional(readOnly = true)
    public List<AiConversation> list(UUID userId, Instant beforeTime, UUID beforeId, int requestedLimit) {
        int limit = normalizeLimit(requestedLimit);
        if ((beforeTime == null) != (beforeId == null)) {
            throw new AgentApplicationException("AGENT_CURSOR_INVALID", "会话游标无效");
        }
        return repository.listConversations(userId, beforeTime, beforeId, limit);
    }

    @Transactional
    public AiConversation rename(UUID userId, UUID conversationId, String requestedTitle, long expectedVersion) {
        String title = normalizeTitle(requestedTitle);
        if (!repository.renameConversation(userId, conversationId, title, expectedVersion, clock.instant())) {
            if (repository.findConversation(userId, conversationId).isEmpty()) {
                throw new AgentApplicationException(
                        "AGENT_CONVERSATION_NOT_FOUND", "AI 会话不存在或不可访问");
            }
            throw new AgentApplicationException(
                    "AGENT_CONVERSATION_VERSION_CONFLICT", "AI 会话已更新，请刷新后重试");
        }
        return get(userId, conversationId);
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        repository.softDeleteConversation(userId, conversationId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<AiMessage> listMessages(
            UUID userId, UUID conversationId, Long beforeSequence, int requestedLimit) {
        get(userId, conversationId);
        return repository.listMessages(userId, conversationId, beforeSequence, normalizeLimit(requestedLimit));
    }

    private static String normalizeTitle(String requestedTitle) {
        String title = requestedTitle == null || requestedTitle.isBlank() ? "未命名对话" : requestedTitle.strip();
        if (title.length() > 128) {
            throw new AgentApplicationException("AGENT_TITLE_TOO_LONG", "会话标题不能超过 128 个字符");
        }
        return title;
    }

    private static int normalizeLimit(int requestedLimit) {
        if (requestedLimit < 1) {
            throw new AgentApplicationException("AGENT_PAGE_SIZE_INVALID", "分页大小必须大于零");
        }
        return Math.min(requestedLimit, MAX_PAGE_SIZE);
    }
}
