package com.minipay.commerce.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(CommerceSecurityConfigurationTest.ProbeController.class)
@Import(CommerceSecurityConfiguration.class)
class CommerceSecurityConfigurationTest {
    private static final UUID RUN_ID = UUID.fromString("0198f400-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0198f400-0000-7000-8000-000000000002");
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean JdbcTemplate jdbcTemplate;

    @Test
    void requiresEveryDelegatedIdentityAndPurposeClaim() throws Exception {
        mvc.perform(get("/internal/v1/agent/merchants/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("commerce-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", RUN_ID.toString())
                                .claim("user_id", USER_ID.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "ORDER_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_commerce.agent.catalog.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/internal/v1/agent/merchants/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("commerce-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", RUN_ID.toString())
                                .claim("user_id", USER_ID.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "FOOD_DISCOVERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_commerce.agent.catalog.read"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void consumerTokenCannotEnterInternalApi() throws Exception {
        mvc.perform(get("/internal/v1/agent/merchants/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_commerce.use"))))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/internal/v1/agent/merchants/probe")
        String probe() { return "ok"; }
    }
}
