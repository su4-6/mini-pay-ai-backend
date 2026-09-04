package com.minipay.agent.application.service;

import com.minipay.agent.application.port.DelegatedTokenProvider;
import com.minipay.agent.domain.model.tool.AgentToolDefinition;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DelegatedAuthorizationService {
    private static final Map<String, String> PURPOSES = Map.ofEntries(
            Map.entry("contact.resolveExactFriend", "CONTACT_LOOKUP"),
            Map.entry("recipient.resolveExactMobile", "RECIPIENT_RESOLUTION"),
            Map.entry("wallet.getSummary", "WALLET_QUERY"),
            Map.entry("wallet.listBills", "BILL_QUERY"),
            Map.entry("wallet.aggregateBills", "BILL_ANALYSIS"),
            Map.entry("payment.prepareTransfer", "TRANSFER_PREPARE"),
            Map.entry("payment.getTransfer", "TRANSFER_QUERY"),
            Map.entry("commerce.searchNearbyStores", "FOOD_DISCOVERY"),
            Map.entry("commerce.getStoreMenu", "FOOD_DISCOVERY"),
            Map.entry("commerce.getFoodCart", "FOOD_CART"),
            Map.entry("commerce.updateFoodCart", "FOOD_CART"),
            Map.entry("commerce.listFoodAddresses", "FOOD_CHECKOUT"),
            Map.entry("commerce.prepareFoodCheckout", "FOOD_CHECKOUT"),
            Map.entry("commerce.getFoodOrder", "ORDER_QUERY"),
            Map.entry("commerce.prepareFoodCancellation", "ORDER_CANCEL"));

    private final AgentToolPolicy toolPolicy;
    private final DelegatedTokenProvider tokenProvider;
    private final Clock clock;

    @Autowired
    public DelegatedAuthorizationService(
            AgentToolPolicy toolPolicy,
            DelegatedTokenProvider tokenProvider) {
        this(toolPolicy, tokenProvider, Clock.systemUTC());
    }

    DelegatedAuthorizationService(
            AgentToolPolicy toolPolicy,
            DelegatedTokenProvider tokenProvider,
            Clock clock) {
        this.toolPolicy = toolPolicy;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    public DelegatedTokenProvider.DelegatedToken exchangeForTool(
            String androidAccessToken,
            UUID runId,
            String toolName) {
        if (androidAccessToken == null || androidAccessToken.isBlank()) {
            throw new AgentApplicationException(
                    "AGENT_SUBJECT_TOKEN_REQUIRED", "用户授权已失效，请重新进入任务");
        }
        AgentToolDefinition tool = toolPolicy.prepareDelegation(toolName);
        String purpose = PURPOSES.get(tool.name());
        if (purpose == null) {
            throw new AgentApplicationException(
                    "AGENT_TOOL_PURPOSE_MISSING", "工具授权策略不完整");
        }
        DelegatedTokenProvider.DelegatedToken token = tokenProvider.exchange(
                new DelegatedTokenProvider.TokenExchangeRequest(
                        androidAccessToken,
                        runId,
                        tool.targetAudience(),
                        tool.requiredScopes(),
                        purpose));
        if (!token.scopes().containsAll(tool.requiredScopes())
                || !token.expiresAt().isAfter(clock.instant())) {
            throw new AgentApplicationException(
                    "AGENT_DELEGATED_TOKEN_INVALID", "下游授权无效，请重试");
        }
        return token;
    }
}
