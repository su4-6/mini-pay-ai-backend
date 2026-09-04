package com.minipay.agent.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigurationTest.ProbeController.class)
@Import({SecurityConfiguration.class, ApiSecurityProblemHandler.class})
class SecurityConfigurationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void requiresConsumerAudienceAndConversationScope() throws Exception {
        mvc.perform(post("/api/v1/agent/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_wallet.read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

        mvc.perform(post("/api/v1/agent/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agent.conversation"))))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class ProbeController {
        @PostMapping("/api/v1/agent/probe")
        String probe() { return "ok"; }
    }
}
