package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.agent.application.port.DelegatedTokenProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelegatedAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-08T04:00:00Z");

    @Test
    void exchangesOnlyTheRegisteredToolScopeAndPurpose() {
        CapturingTokenProvider provider = new CapturingTokenProvider(
                new DelegatedTokenProvider.DelegatedToken(
                        "delegated", "Bearer", NOW.plusSeconds(120), Set.of("wallet.agent.summary")));
        DelegatedAuthorizationService service = new DelegatedAuthorizationService(
                new AgentToolPolicy(new AgentToolRegistry()),
                provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID runId = UUID.randomUUID();

        DelegatedTokenProvider.DelegatedToken token = service.exchangeForTool(
                "android-token", runId, "wallet.getSummary");

        assertThat(token.accessToken()).isEqualTo("delegated");
        assertThat(provider.request.audience()).isEqualTo("wallet-internal");
        assertThat(provider.request.scopes()).containsExactly("wallet.agent.summary");
        assertThat(provider.request.purpose()).isEqualTo("WALLET_QUERY");
        assertThat(provider.request.runId()).isEqualTo(runId);
    }

    @Test
    void rejectsTokenMissingTheRequestedScope() {
        CapturingTokenProvider provider = new CapturingTokenProvider(
                new DelegatedTokenProvider.DelegatedToken(
                        "delegated", "Bearer", NOW.plusSeconds(120), Set.of()));
        DelegatedAuthorizationService service = new DelegatedAuthorizationService(
                new AgentToolPolicy(new AgentToolRegistry()),
                provider,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.exchangeForTool(
                "android-token", UUID.randomUUID(), "wallet.getSummary"))
                .isInstanceOf(AgentApplicationException.class)
                .extracting("code")
                .isEqualTo("AGENT_DELEGATED_TOKEN_INVALID");
    }

    @Test
    void neverSendsUnregisteredToolsToTokenExchange() {
        CapturingTokenProvider provider = new CapturingTokenProvider(null);
        DelegatedAuthorizationService service = new DelegatedAuthorizationService(
                new AgentToolPolicy(new AgentToolRegistry()),
                provider,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.exchangeForTool(
                "android-token", UUID.randomUUID(), "payment.confirm"))
                .isInstanceOf(AgentApplicationException.class)
                .extracting("code")
                .isEqualTo("AGENT_TOOL_NOT_REGISTERED");
        assertThat(provider.request).isNull();
    }

    private static final class CapturingTokenProvider implements DelegatedTokenProvider {
        private final DelegatedToken response;
        private TokenExchangeRequest request;

        private CapturingTokenProvider(DelegatedToken response) {
            this.response = response;
        }

        @Override
        public DelegatedToken exchange(TokenExchangeRequest request) {
            this.request = request;
            return response;
        }
    }
}
