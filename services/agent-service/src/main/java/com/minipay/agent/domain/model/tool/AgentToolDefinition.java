package com.minipay.agent.domain.model.tool;

import java.util.Set;

public record AgentToolDefinition(
        String name,
        int schemaVersion,
        ToolRiskLevel riskLevel,
        String targetAudience,
        Set<String> requiredScopes) {

    public AgentToolDefinition {
        requiredScopes = Set.copyOf(requiredScopes);
        if (riskLevel == ToolRiskLevel.W2) {
            throw new IllegalArgumentException("W2 tools must never be registered for model use");
        }
    }
}
