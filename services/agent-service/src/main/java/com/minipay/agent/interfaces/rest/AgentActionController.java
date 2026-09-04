package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.AgentBusinessOrchestrator;
import com.minipay.agent.application.service.AgentBusinessOrchestrator.ActionCommand;
import com.minipay.agent.application.service.AgentRunApplicationService;
import com.minipay.agent.application.service.MemoryConversationService;
import com.minipay.agent.domain.model.ai.AgentRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/ai/runs/{runId}/actions")
public class AgentActionController {
    private final AgentBusinessOrchestrator orchestrator;
    private final AgentRunApplicationService runs;
    private final MemoryConversationService memory;

    public AgentActionController(
            AgentBusinessOrchestrator orchestrator,
            AgentRunApplicationService runs,
            MemoryConversationService memory) {
        this.orchestrator = orchestrator;
        this.runs = runs;
        this.memory = memory;
    }

    @PostMapping
    public AgentRunController.RunResponse execute(
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ActionRequest request) {
        UUID userId = userId(jwt);
        if ("CONFIRM_MEMORY".equals(request.action())) {
            memory.confirm(userId, runId, requireCandidate(request.candidateId()));
        } else if ("DISMISS_MEMORY".equals(request.action())) {
            memory.dismiss(userId, runId, requireCandidate(request.candidateId()));
        } else {
            orchestrator.continueAction(userId, runId, new ActionCommand(
                    request.action(), request.merchantId(), request.skuId(), request.quantity(),
                    request.optionIds(), request.expectedCartVersion(), request.addressId(),
                    request.orderId(), request.transferId(), request.fulfillmentType(),
                    request.recipientUserId()), jwt.getTokenValue());
        }
        AgentRun run = runs.getRun(userId, runId);
        return new AgentRunController.RunResponse(
                run.id(), run.conversationId(), run.status().name(), run.businessRefType(),
                run.businessRefId(), "/api/v1/agent/ai/runs/" + run.id() + "/events",
                false, run.version(), run.createdAt(), run.updatedAt());
    }

    private static UUID userId(Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        return UUID.fromString(claim == null ? jwt.getSubject() : claim);
    }

    private static UUID requireCandidate(UUID candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("candidateId is required");
        }
        return candidateId;
    }

    public record ActionRequest(
            @NotBlank String action,
            UUID merchantId,
            UUID skuId,
            Integer quantity,
            List<UUID> optionIds,
            Long expectedCartVersion,
            UUID addressId,
            UUID orderId,
            UUID transferId,
            String fulfillmentType,
            UUID recipientUserId,
            UUID candidateId) {
    }
}
