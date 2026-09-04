package com.minipay.payment.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
    // MerchantApiSignatureFilter (auto-registered servlet filter) needs these; not present in the web slice.
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean MerchantSecretCipher secrets;

    @Test
    void rejectsMissingTokenWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/management/probe").header("X-Request-Id", "req-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(jsonPath("$.requestId").value("req-1"));
    }

    @Test
    void preventsConsumerTokenFromEnteringManagementBoundary() throws Exception {
        mvc.perform(get("/api/v1/management/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.portal"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void acceptsMatchingAudienceAndScope() throws Exception {
        mvc.perform(get("/api/v1/management/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.portal"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void separatesDashboardReadFromMerchantPermissions() throws Exception {
        mvc.perform(get("/api/v1/ops/dashboard").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/ops/dashboard").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.dashboard.read"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantReadScopeCannotPerformWrites() throws Exception {
        mvc.perform(post("/api/v1/ops/merchants/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/ops/merchants/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.write"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantApplyReadScopeCannotWrite() throws Exception {
        mvc.perform(get("/api/v1/ops/merchant-applies/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.read"))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/ops/merchant-applies/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/ops/merchant-applies/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.merchant.write"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rechargeIntentRequiresTheRechargeWriteScope() throws Exception {
        mvc.perform(post("/api/v1/recharge-intents/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.recharge.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/recharge-intents/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.recharge.write"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void applicationReadScopeCannotPerformWrites() throws Exception {
        mvc.perform(post("/api/v1/ops/applications/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.application.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/ops/applications/probe").with(jwt()
                .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.application.read"))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/ops/applications/probe").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ops.application.write"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void consumerMerchantScopesExposeOnlyOnboardingSurface() throws Exception {
        mvc.perform(get("/api/v1/merchant/onboardings").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_merchant.portal.read"))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/merchant/onboardings").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_merchant.portal.write"))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/merchant/merchants").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_merchant.portal.read"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/merchant/merchants/probe/applications/probe/secret-reset").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_merchant.portal.write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void everySignedInPortalUserCanUploadMerchantImagesWithoutABusinessScope() throws Exception {
        mvc.perform(post("/api/v1/merchant/image-uploads").with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/merchant/image-uploads").with(jwt()
                        .jwt(token -> token.audience(List.of("merchant-api")))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/merchant/image-uploads").with(jwt()
                        .jwt(token -> token.audience(List.of("management-api")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentTransferPreparationRequiresBoundDelegationClaims() throws Exception {
        UUID runId = UUID.fromString("0198f400-0000-7000-8000-000000000005");
        UUID userId = UUID.fromString("0198f400-0000-7000-8000-000000000006");
        mvc.perform(post("/internal/v1/agent/transfer-intents").with(jwt()
                        .jwt(token -> token.audience(List.of("payment-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "WALLET_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.agent.transfer.prepare"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/internal/v1/agent/transfer-intents").with(jwt()
                        .jwt(token -> token.audience(List.of("payment-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "TRANSFER_PREPARE"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.agent.transfer.prepare"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentTransferResultRequiresReadScopeAndQueryPurpose() throws Exception {
        UUID runId = UUID.fromString("0198f400-0000-7000-8000-000000000005");
        UUID userId = UUID.fromString("0198f400-0000-7000-8000-000000000006");
        String path = "/internal/v1/agent/transfer-orders/0198f400-0000-7000-8000-000000000008";
        mvc.perform(get(path).with(jwt()
                        .jwt(token -> token.audience(List.of("payment-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "TRANSFER_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.agent.transfer.prepare"))))
                .andExpect(status().isForbidden());

        mvc.perform(get(path).with(jwt()
                        .jwt(token -> token.audience(List.of("payment-internal"))
                .claim("azp", "minipay-agent-service")
                                .claim("run_id", runId.toString())
                                .claim("user_id", userId.toString())
                                .claim("device_id", "android-1")
                                .claim("purpose", "TRANSFER_QUERY"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.agent.transfer.read"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void foodPaymentLookupUsesReadScope() throws Exception {
        String path = "/api/v1/food-orders/0198f400-0000-7000-8000-000000000007/payment-order";
        mvc.perform(get(path).with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.order.write"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt()
                        .jwt(token -> token.audience(List.of("consumer-api")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment.order.read"))))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/management/probe")
        String probe() { return "ok"; }

        @GetMapping("/api/v1/ops/dashboard")
        String dashboard() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/ops/merchants/probe")
        String merchantWrite() { return "ok"; }

        @GetMapping("/api/v1/ops/applications/probe")
        String applicationRead() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/ops/applications/probe")
        String applicationWrite() { return "ok"; }

        @GetMapping("/api/v1/ops/merchant-applies/probe")
        String applyRead() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/ops/merchant-applies/probe")
        String applyWrite() { return "ok"; }

        @GetMapping("/api/v1/merchant/onboardings")
        String consumerOnboardingRead() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/merchant/onboardings")
        String consumerOnboardingWrite() { return "ok"; }

        @GetMapping("/api/v1/merchant/merchants")
        String merchantList() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping(
                "/api/v1/merchant/merchants/probe/applications/probe/secret-reset")
        String merchantSecretReset() { return "ok"; }

        @org.springframework.web.bind.annotation.PostMapping("/internal/v1/agent/transfer-intents")
        String agentTransfer() { return "ok"; }

        @GetMapping("/internal/v1/agent/transfer-orders/{transferId}")
        String agentTransferResult() { return "ok"; }

        @GetMapping("/api/v1/food-orders/{foodOrderId}/payment-order")
        String foodPayment() { return "ok"; }
    }
}
