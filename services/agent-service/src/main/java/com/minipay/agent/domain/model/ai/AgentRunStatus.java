package com.minipay.agent.domain.model.ai;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum AgentRunStatus {
    RECEIVED,
    UNDERSTANDING,
    WAITING_INPUT,
    EXECUTING_TOOL,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<AgentRunStatus, Set<AgentRunStatus>> TRANSITIONS = Map.of(
            RECEIVED, EnumSet.of(UNDERSTANDING, FAILED, CANCELLED),
            UNDERSTANDING, EnumSet.of(WAITING_INPUT, EXECUTING_TOOL, COMPLETED, FAILED, CANCELLED),
            WAITING_INPUT, EnumSet.of(UNDERSTANDING, CANCELLED),
            EXECUTING_TOOL, EnumSet.of(UNDERSTANDING, WAITING_CONFIRMATION, FAILED, CANCELLED),
            WAITING_CONFIRMATION, EnumSet.of(UNDERSTANDING, COMPLETED, FAILED, CANCELLED),
            COMPLETED, EnumSet.noneOf(AgentRunStatus.class),
            FAILED, EnumSet.noneOf(AgentRunStatus.class),
            CANCELLED, EnumSet.noneOf(AgentRunStatus.class));

    public boolean canTransitionTo(AgentRunStatus target) {
        return this == target || TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
