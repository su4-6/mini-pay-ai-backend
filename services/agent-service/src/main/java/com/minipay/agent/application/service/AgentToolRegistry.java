package com.minipay.agent.application.service;

import com.minipay.agent.domain.model.tool.AgentToolDefinition;
import com.minipay.agent.domain.model.tool.ToolRiskLevel;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class AgentToolRegistry {
    private final Map<String, AgentToolDefinition> tools;

    public AgentToolRegistry() {
        LinkedHashMap<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
        register(definitions, tool("contact.resolveExactFriend", ToolRiskLevel.R0,
                "identity-internal", "agent.contact.read"));
        register(definitions, tool("recipient.resolveExactMobile", ToolRiskLevel.R0,
                "identity-internal", "identity.agent.recipient.resolve"));
        register(definitions, tool("wallet.getSummary", ToolRiskLevel.R0,
                "wallet-internal", "wallet.agent.summary"));
        register(definitions, tool("wallet.listBills", ToolRiskLevel.R0,
                "wallet-internal", "wallet.agent.bills.read"));
        register(definitions, tool("wallet.aggregateBills", ToolRiskLevel.R0,
                "wallet-internal", "wallet.agent.bills.aggregate"));
        register(definitions, tool("payment.prepareTransfer", ToolRiskLevel.W1,
                "payment-internal", "payment.agent.transfer.prepare"));
        register(definitions, tool("payment.getTransfer", ToolRiskLevel.R0,
                "payment-internal", "payment.agent.transfer.read"));
        register(definitions, tool("commerce.searchNearbyStores", ToolRiskLevel.R0,
                "commerce-internal", "commerce.agent.catalog.read"));
        register(definitions, tool("commerce.getStoreMenu", ToolRiskLevel.R0,
                "commerce-internal", "commerce.agent.catalog.read"));
        register(definitions, tool("commerce.getFoodCart", ToolRiskLevel.R0,
                "commerce-internal", "commerce.agent.catalog.read"));
        register(definitions, tool("commerce.updateFoodCart", ToolRiskLevel.W1,
                "commerce-internal", "commerce.agent.cart.write"));
        register(definitions, tool("commerce.listFoodAddresses", ToolRiskLevel.R0,
                "commerce-internal", "commerce.agent.catalog.read"));
        register(definitions, tool("commerce.prepareFoodCheckout", ToolRiskLevel.W1,
                "commerce-internal", "commerce.agent.checkout.prepare"));
        register(definitions, tool("commerce.getFoodOrder", ToolRiskLevel.R0,
                "commerce-internal", "commerce.agent.order.read"));
        register(definitions, tool("commerce.prepareFoodCancellation", ToolRiskLevel.W1,
                "commerce-internal", "commerce.agent.cancel.prepare"));
        this.tools = Map.copyOf(definitions);
    }

    public Optional<AgentToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<AgentToolDefinition> modelVisibleTools() {
        return tools.values();
    }

    private static AgentToolDefinition tool(
            String name, ToolRiskLevel risk, String audience, String requiredScope) {
        return new AgentToolDefinition(name, 1, risk, audience, Set.of(requiredScope));
    }

    private static void register(
            Map<String, AgentToolDefinition> definitions, AgentToolDefinition definition) {
        if (definitions.putIfAbsent(definition.name(), definition) != null) {
            throw new IllegalStateException("Duplicate Agent tool: " + definition.name());
        }
    }
}
