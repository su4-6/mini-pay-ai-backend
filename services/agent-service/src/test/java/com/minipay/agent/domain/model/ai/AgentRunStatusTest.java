package com.minipay.agent.domain.model.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentRunStatusTest {
    @Test
    void allowsOnlyDesignedTransitions() {
        assertThat(AgentRunStatus.RECEIVED.canTransitionTo(AgentRunStatus.UNDERSTANDING)).isTrue();
        assertThat(AgentRunStatus.UNDERSTANDING.canTransitionTo(AgentRunStatus.WAITING_INPUT)).isTrue();
        assertThat(AgentRunStatus.EXECUTING_TOOL.canTransitionTo(AgentRunStatus.WAITING_CONFIRMATION)).isTrue();
        assertThat(AgentRunStatus.WAITING_CONFIRMATION.canTransitionTo(AgentRunStatus.UNDERSTANDING)).isTrue();
        assertThat(AgentRunStatus.WAITING_CONFIRMATION.canTransitionTo(AgentRunStatus.COMPLETED)).isTrue();

        assertThat(AgentRunStatus.RECEIVED.canTransitionTo(AgentRunStatus.COMPLETED)).isFalse();
        assertThat(AgentRunStatus.COMPLETED.canTransitionTo(AgentRunStatus.UNDERSTANDING)).isFalse();
        assertThat(AgentRunStatus.FAILED.canTransitionTo(AgentRunStatus.RECEIVED)).isFalse();
    }

    @Test
    void identifiesTerminalStates() {
        assertThat(AgentRunStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.FAILED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.WAITING_CONFIRMATION.isTerminal()).isFalse();
    }
}
