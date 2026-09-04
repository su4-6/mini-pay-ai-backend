package com.minipay.agent.application.service;

import com.minipay.agent.domain.model.tool.AgentToolDefinition;
import com.minipay.agent.domain.model.tool.ToolRiskLevel;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class AgentToolPolicy {
    private final AgentToolRegistry registry;

    public AgentToolPolicy(AgentToolRegistry registry) {
        this.registry = registry;
    }

    public AgentToolDefinition authorize(String toolName, Set<String> delegatedScopes) {
        AgentToolDefinition definition = prepareDelegation(toolName);
        if (!delegatedScopes.containsAll(definition.requiredScopes())) {
            throw new AgentApplicationException(
                    "AGENT_TOOL_SCOPE_DENIED", "当前授权不足，无法执行该能力");
        }
        return definition;
    }

    public AgentToolDefinition prepareDelegation(String toolName) {
        AgentToolDefinition definition = registry.find(toolName)
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_TOOL_NOT_REGISTERED", "请求的能力未开放"));
        if (definition.riskLevel() == ToolRiskLevel.W2) {
            throw new AgentApplicationException(
                    "AGENT_W2_TOOL_FORBIDDEN", "该操作必须在原生安全页面确认");
        }
        return definition;
    }
}
