package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.AgentRunApplicationService;
import com.minipay.agent.application.service.AgentRunProcessor;
import com.minipay.agent.application.service.AgentApplicationException;
import com.minipay.agent.domain.model.ai.AgentRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/ai")
public class AgentRunController {
    private final AgentRunApplicationService runs;
    private final AgentRunProcessor processor;

    public AgentRunController(AgentRunApplicationService runs, AgentRunProcessor processor) {
        this.runs = runs;
        this.processor = processor;
    }

    @PostMapping("/conversations/{conversationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse create(
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AgentRunApplicationService.CreateRunResult result = runs.createRun(
                userId(jwt), conversationId, request.clientMessageId(), idempotencyKey,
                request.message(), request.contextVersion());
        if (!result.replayed()) {
            processor.start(userId(jwt), result.run().id(), result.sanitizedMessage(),
                    result.transientExactMobile(), request.locationContextId(), jwt.getTokenValue());
        }
        return response(result.run(), result.replayed());
    }

    @GetMapping("/runs/{runId}")
    public RunResponse get(@PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        return response(runs.getRun(userId(jwt), runId), false);
    }

    @GetMapping("/runs")
    public RunPage active(
            @RequestParam(defaultValue = "true") boolean active,
            @AuthenticationPrincipal Jwt jwt) {
        if (!active) {
            throw new AgentApplicationException(
                    "AGENT_ACTIVE_FILTER_REQUIRED", "当前接口只支持查询活动任务");
        }
        return new RunPage(runs.listActiveRuns(userId(jwt)).stream()
                .map(run -> response(run, false)).toList());
    }

    @PostMapping("/runs/{runId}/cancel")
    public RunResponse cancel(@PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        return response(runs.cancel(userId(jwt), runId), false);
    }

    private static RunResponse response(AgentRun run, boolean replayed) {
        return new RunResponse(
                run.id(), run.conversationId(), run.status().name(), run.businessRefType(),
                run.businessRefId(), "/api/v1/agent/ai/runs/" + run.id() + "/events",
                replayed, run.version(), run.createdAt(), run.updatedAt());
    }

    private static UUID userId(Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        return UUID.fromString(claim == null ? jwt.getSubject() : claim);
    }

    public record CreateRunRequest(
            @NotNull UUID clientMessageId,
            @NotBlank @Size(max = 1000) String message,
            @Min(0) long contextVersion,
            UUID locationContextId) {
    }

    public record RunResponse(
            UUID runId,
            UUID conversationId,
            String status,
            String businessRefType,
            String businessRefId,
            String eventsUrl,
            boolean replayed,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record RunPage(List<RunResponse> items) {
    }
}
