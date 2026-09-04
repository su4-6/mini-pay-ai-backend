package com.minipay.commerce.infrastructure.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class CommerceSecurityConfiguration {
    @Bean
    SecurityFilterChain commerceSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // These endpoints are authenticated by ServiceHmacFilter, not OAuth.
                        .requestMatchers("/internal/v1/food-handoffs/consume",
                                "/internal/v1/food-location-contexts/**", "/internal/v1/yshop/**").permitAll()
                        .requestMatchers("/internal/v2/agent/food/stores/**")
                        .access(agentDelegation("commerce.agent.catalog.read", Set.of("FOOD_DISCOVERY")))
                        .requestMatchers("/internal/v2/agent/food/carts/**")
                        .access(agentDelegation("commerce.agent.cart.write", Set.of("FOOD_CART")))
                        .requestMatchers("/internal/v2/agent/food/addresses")
                        .access(agentDelegation("commerce.agent.checkout.prepare", Set.of("FOOD_CHECKOUT")))
                        .requestMatchers("/internal/v2/agent/food/checkout-quotes")
                        .access(agentDelegation("commerce.agent.checkout.prepare", Set.of("FOOD_CHECKOUT")))
                        .requestMatchers("/internal/v2/agent/food/orders/*/cancellation-preview")
                        .access(agentDelegation("commerce.agent.cancel.prepare",
                                Set.of("ORDER_CANCEL", "ORDER_REFUND")))
                        .requestMatchers("/internal/v2/agent/food/orders/**")
                        .access(agentDelegation("commerce.agent.order.read", Set.of("ORDER_QUERY")))
                        .requestMatchers("/internal/v1/agent/merchants/**")
                        .access(agentDelegation("commerce.agent.catalog.read", Set.of("FOOD_DISCOVERY")))
                        .requestMatchers("/internal/v1/agent/carts/**")
                        .access(agentDelegation("commerce.agent.cart.write", Set.of("FOOD_CART")))
                        .requestMatchers("/internal/v1/agent/checkout-quotes/**")
                        .access(agentDelegation("commerce.agent.checkout.prepare", Set.of("FOOD_CHECKOUT")))
                        .requestMatchers("/internal/v1/agent/orders/*/cancellation-preview")
                        .access(agentDelegation("commerce.agent.cancel.prepare",
                                Set.of("ORDER_CANCEL", "ORDER_REFUND")))
                        .requestMatchers("/internal/v1/agent/orders/**")
                        .access(agentDelegation("commerce.agent.order.read", Set.of("ORDER_QUERY")))
                        .requestMatchers("/api/v1/ops/food-orders/**")
                        .access(audienceAndScope("management-api", "ops.portal"))
                        .requestMatchers("/api/v1/admin/orders/food-orders/**")
                        .access(audienceAndScope("admin-api", "admin.order.read"))
                        .requestMatchers("/api/v1/commerce/**")
                        .access(audienceAndScope("consumer-api", "commerce.use"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }

    @Bean
    JwtDecoder commerceJwtDecoder(
            @Value("${minipay.security.issuer}") String issuer,
            @Value("${minipay.security.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null
                        && audiences.stream().anyMatch(
                        List.of("commerce-internal", "consumer-api", "management-api", "admin-api")::contains))));
        return decoder;
    }

    private static AuthorizationManager<RequestAuthorizationContext> agentDelegation(
            String scope,
            Set<String> purposes) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken authentication)) {
                return new AuthorizationDecision(false);
            }
            Jwt jwt = authentication.getToken();
            boolean validRun;
            try {
                UUID.fromString(jwt.getClaimAsString("run_id"));
                UUID.fromString(jwt.getClaimAsString("user_id"));
                validRun = true;
            } catch (RuntimeException exception) {
                validRun = false;
            }
            boolean granted = validRun
                    && jwt.getAudience().contains("commerce-internal")
                    && "minipay-agent-service".equals(jwt.getClaimAsString("azp"))
                    && jwt.getClaimAsString("device_id") != null
                    && purposes.contains(jwt.getClaimAsString("purpose"))
                    && authentication.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(granted);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> audienceAndScope(
            String audience,
            String scope) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(
                    jwt.getToken().getAudience().contains(audience)
                            && jwt.getAuthorities().stream().anyMatch(
                            authority -> authority.getAuthority().equals("SCOPE_" + scope)));
        };
    }
}
