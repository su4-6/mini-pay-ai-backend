package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.agent.domain.model.tool.ToolRiskLevel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentToolPolicyTest {
    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final AgentToolPolicy policy = new AgentToolPolicy(registry);

    @Test
    void modelCatalogContainsOnlyR0AndW1Tools() {
        assertThat(registry.modelVisibleTools())
                .isNotEmpty()
                .allSatisfy(tool -> assertThat(tool.riskLevel()).isNotEqualTo(ToolRiskLevel.W2));
        assertThat(registry.find("payment.confirmTransfer")).isEmpty();
        assertThat(registry.find("commerce.createOrder")).isEmpty();
        assertThat(registry.find("payment.confirmPayment")).isEmpty();
    }

    @Test
    void requiresExactDelegatedScopeAndRejectsUnknownTools() {
        assertThat(policy.authorize(
                        "wallet.getSummary", Set.of("wallet.agent.summary"))
                .targetAudience()).isEqualTo("wallet-internal");

        assertThatThrownBy(() -> policy.authorize("wallet.getSummary", Set.of("wallet.read")))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_TOOL_SCOPE_DENIED"));
        assertThatThrownBy(() -> policy.authorize("http://internal/admin", Set.of("*")))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_TOOL_NOT_REGISTERED"));
    }
}
