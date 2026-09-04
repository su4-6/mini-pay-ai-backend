package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.AgentApplicationException;
import com.minipay.agent.application.service.AgentRunApplicationService;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import com.minipay.agent.infrastructure.sse.RunEventBroadcaster;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent/ai/runs")
public class AgentRunSseController {
    private static final int BACKLOG_PAGE_SIZE = 500;

    private final AgentRunApplicationService runs;
    private final RunEventBroadcaster broadcaster;

    public AgentRunSseController(AgentRunApplicationService runs, RunEventBroadcaster broadcaster) {
        this.runs = runs;
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        UUID userId = UUID.fromString(claim == null ? jwt.getSubject() : claim);
        long afterSequence = parseCursor(lastEventId);
        AgentRun run = runs.getRun(userId, runId);
        RunEventBroadcaster.Subscription subscription = broadcaster.subscribe(run, afterSequence);
        try {
            long cursor = afterSequence;
            List<AgentRunEvent> backlog = new java.util.ArrayList<>();
            while (true) {
                List<AgentRunEvent> page = runs.listEvents(userId, runId, cursor, BACKLOG_PAGE_SIZE);
                backlog.addAll(page);
                if (page.size() < BACKLOG_PAGE_SIZE) {
                    break;
                }
                cursor = page.getLast().sequenceNo();
            }
            subscription.sendBacklog(backlog);
            return subscription.emitter();
        } catch (RuntimeException exception) {
            subscription.emitter().completeWithError(exception);
            throw exception;
        }
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new AgentApplicationException("AGENT_EVENT_CURSOR_INVALID", "事件游标无效");
        }
    }
}
