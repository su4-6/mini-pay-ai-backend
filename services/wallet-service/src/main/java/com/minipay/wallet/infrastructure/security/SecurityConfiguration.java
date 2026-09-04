package com.minipay.wallet.infrastructure.security;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProblemHandler problems)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**")
                        .access(adminAudienceScope("admin.wallet.read"))
                        .requestMatchers("/api/v1/management/**")
                        .access(audienceAndScope("management-api", "ops.portal"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/wallets/**")
                        .access(consumerOrMerchantWalletRead())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/wallets/me/bills/*/management")
                        .access(audienceAndScope("consumer-api", "wallet.write"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/wallets/me/bill-tags")
                        .access(audienceAndScope("consumer-api", "wallet.write"))
                        .requestMatchers("/internal/v1/wallet-accounts/**")
                        .access(audienceAndScope("wallet-internal", "wallet.account.resolve"))
                        .requestMatchers("/internal/v1/agent/wallet-summary")
                        .access(agentDelegation("wallet.agent.summary", "WALLET_QUERY"))
                        .requestMatchers("/internal/v1/agent/wallet-bills")
                        .access(agentDelegation("wallet.agent.bills.read", "BILL_QUERY"))
                        .requestMatchers("/internal/v1/agent/wallet-bill-aggregations")
                        .access(agentDelegation("wallet.agent.bills.aggregate", "BILL_ANALYSIS"))
                        .requestMatchers("/internal/v1/wallet-postings/**")
                        .access(audienceAndScope("wallet-internal", "wallet.posting.write"))
                        .requestMatchers("/internal/v1/tcc/**")
                        .access(audienceAndScope("wallet-internal", "wallet.tcc"))
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${minipay.security.issuer}") String issuer,
                          @Value("${minipay.security.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null
                        && audiences.stream().anyMatch(
                        List.of("management-api", "admin-api", "consumer-api", "merchant-api", "wallet-internal")::contains))));
        return decoder;
    }

    private static AuthorizationManager<RequestAuthorizationContext> audienceAndScope(
            String audience, String scope) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            boolean granted = jwt.getToken().getAudience().contains(audience)
                    && jwt.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(granted);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> agentDelegation(
            String scope, String purpose) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken authentication)) {
                return new AuthorizationDecision(false);
            }
            Jwt jwt = authentication.getToken();
            boolean identifiersValid;
            try {
                UUID.fromString(jwt.getClaimAsString("run_id"));
                UUID.fromString(jwt.getClaimAsString("user_id"));
                identifiersValid = true;
            } catch (RuntimeException exception) {
                identifiersValid = false;
            }
            boolean granted = identifiersValid
                    && jwt.getAudience().contains("wallet-internal")
                    && "minipay-agent-service".equals(jwt.getClaimAsString("azp"))
                    && purpose.equals(jwt.getClaimAsString("purpose"))
                    && jwt.getClaimAsString("device_id") != null
                    && authentication.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(granted);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> adminAudienceScope(String scope) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            List<String> roles = jwt.getToken().getClaimAsStringList("roles");
            boolean systemRole = roles != null && roles.stream().anyMatch(role ->
                    role.equals("system_super_admin") || role.equals("system_account_admin")
                            || role.equals("system_auditor"));
            boolean granted = systemRole && jwt.getToken().getAudience().contains("admin-api")
                    && jwt.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(granted);
        };
    }

    /** Merchant portal may only read the wallet belonging to its own authenticated owner. */
    private static AuthorizationManager<RequestAuthorizationContext> consumerOrMerchantWalletRead() {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) return new AuthorizationDecision(false);
            boolean consumer = jwt.getToken().getAudience().contains("consumer-api") && jwt.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("SCOPE_wallet.read"));
            boolean merchant = jwt.getToken().getAudience().contains("merchant-api") && jwt.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("SCOPE_merchant.portal.read"));
            return new AuthorizationDecision(consumer || merchant);
        };
    }
}
