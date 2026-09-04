package com.minipay.wallet.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigurationTest.ProbeController.class)
@Import({SecurityConfiguration.class, ApiSecurityProblemHandler.class})
class SecurityConfigurationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void requiresConsumerAudienceAndWalletScope() throws Exception {
        mvc.perform(get("/api/v1/wallets/me").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_wallet.read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

        mvc.perform(get("/api/v1/wallets/me").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_wallet.read"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentWalletSummaryRequiresBoundDelegationClaims() throws Exception {
        UUID runId = UUID.fromString("0198f400-0000-7000-8000-000000000003");
        UUID userId = UUID.fromString("0198f400-0000-7000-8000-000000000004");
        mvc.perform(get("/internal/v1/agent/wallet-summary").with(jwt()
                        .jwt(token -> token.audience(List.of("wallet-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "BILL_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_wallet.agent.summary"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/internal/v1/agent/wallet-summary").with(jwt()
                        .jwt(token -> token.audience(List.of("wallet-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "WALLET_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_wallet.agent.summary"))))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/wallets/me")
        String probe() { return "ok"; }

        @GetMapping("/internal/v1/agent/wallet-summary")
        String agentSummary() { return "ok"; }
    }
}
