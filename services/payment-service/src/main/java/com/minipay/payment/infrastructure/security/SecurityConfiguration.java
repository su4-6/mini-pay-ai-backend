package com.minipay.payment.infrastructure.security;

import jakarta.servlet.DispatcherType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder merchantPasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProblemHandler problems)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/merchants/**")
                        .access(adminAudienceScope("admin.account.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/orders/**")
                        .access(adminAudienceScope("admin.order.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/dashboard")
                        .access(audienceAndScope("management-api", "ops.dashboard.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/merchants/**")
                        .access(audienceAndScope("management-api", "ops.merchant.read"))
                        .requestMatchers("/api/v1/ops/merchants/**")
                        .access(audienceAndScope("management-api", "ops.merchant.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/applications/**")
                        .access(audienceAndScope("management-api", "ops.application.read"))
                        .requestMatchers("/api/v1/ops/applications/**")
                        .access(audienceAndScope("management-api", "ops.application.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/merchant-applies/**")
                        .access(audienceAndScope("management-api", "ops.merchant.read"))
                        .requestMatchers("/api/v1/ops/merchant-applies/**")
                        .access(audienceAndScope("management-api", "ops.merchant.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/application-applies/**")
                        .access(audienceAndScope("management-api", "ops.application.read"))
                        .requestMatchers("/api/v1/ops/application-applies/**")
                        .access(audienceAndScope("management-api", "ops.application.write"))
                        .requestMatchers("/api/v1/ops/image-uploads/**")
                        .access(audienceAndScope("management-api", "ops.application.write"))
                        .requestMatchers("/api/v1/ops/image-read-urls/**")
                        .access(audienceAndScope("management-api", "ops.application.write"))
                        .requestMatchers("/api/v1/management/**")
                        .access(audienceAndScope("management-api", "ops.portal"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers/**")
                        .access(audienceAndScope("consumer-api", "payment.transfer.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfer-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.transfer.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers/**")
                        .access(audienceAndScope("consumer-api", "payment.transfer.write"))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/transfers/**")
                        .access(audienceAndScope("consumer-api", "payment.transfer.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/recharge-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.recharge.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/recharge-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.recharge.write"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/recharge-intents/**")
                        .access(audienceAndScope("consumer-api", "payment.recharge.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/withdrawal-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.withdrawal.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/withdrawal-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.withdrawal.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/bank-cards/**")
                        .access(audienceAndScope("consumer-api", "payment.bank-card.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/bank-cards/*/balance-queries")
                        .access(audienceAndScope("consumer-api", "payment.bank-card.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/bank-cards/**")
                        .access(audienceAndScope("consumer-api", "payment.bank-card.write"))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/bank-cards/**")
                        .access(audienceAndScope("consumer-api", "payment.bank-card.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/payment-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.order.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/payment-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.order.write"))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/personal-collection-codes/**")
                        .access(audienceAndScope(
                                "consumer-api", "payment.collection-code.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/scan-resolutions")
                        .access(audienceAndScope(
                                "consumer-api", "payment.collection-code.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchant/onboardings")
                        .access(portalAudienceAndScope("merchant.portal.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/image-read-urls")
                        .access(authenticatedPortalAudience())
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/onboardings")
                        .access(portalAudienceAndScope("merchant.portal.write"))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/merchant/onboardings/*")
                        .access(portalAudienceAndScope("merchant.portal.write"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/image-uploads")
                        .access(authenticatedPortalAudience())
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/merchants/*/initialization")
                        .access(portalAudienceAndScope("merchant.portal.write"))
                        // Merchant Web obtains a dedicated merchant-api OAuth token for the same wallet user.
                        // Tenant checks are enforced by MerchantService from the token subject, never from a request field.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/merchant/applications/*/collection-code")
                        .access(audienceAndScope(
                                "merchant-api", "merchant.portal.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchant/**")
                        .access(audienceAndScope(
                                "merchant-api", "merchant.portal.read"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/business-collection-codes/**")
                        .access(audienceAndScope("merchant-api", "merchant.portal.read"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/business-collection-codes/**")
                        .access(audienceAndScope("merchant-api", "merchant.portal.write"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/**")
                        .access(audienceAndScope(
                                "merchant-api", "merchant.portal.write"))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/merchant/**")
                        .access(audienceAndScope("merchant-api", "merchant.portal.write"))
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/merchant/**")
                        .access(audienceAndScope("merchant-api", "merchant.portal.write"))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/merchant/**")
                        .access(audienceAndScope("merchant-api", "merchant.portal.write"))
                        .requestMatchers("/api/v1/ops/**")
                        .access(audienceAndScope("management-api", "ops.portal"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/refunds/**")
                        .access(audienceAndScope("management-api", "payment.refund.write"))
                        .requestMatchers(HttpMethod.GET, "/api/v1/food-orders/**")
                        .access(audienceAndScope("consumer-api", "payment.order.read"))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/agent/transfer-intents")
                        .access(agentDelegation(
                                "payment.agent.transfer.prepare", "TRANSFER_PREPARE"))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/agent/transfer-orders/**")
                        .access(agentDelegation(
                                "payment.agent.transfer.read", "TRANSFER_QUERY"))
                        .requestMatchers("/internal/v1/**")
                        .access(audienceAndScope("payment-internal", "payment.tool.invoke"))
                        // server-to-server merchant-app API is authenticated by
                        // MerchantApiSignatureFilter (HMAC + IP + permission), no JWT.
                        .requestMatchers("/api/v1/merchant-api/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .build();
    }

    /** Identity emits OAuth scopes as a JSON array; normalize it for endpoint scope checks. */
    private static Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            Object rawScopes = jwt.getClaims().get("scope");
            Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
            if (rawScopes instanceof Collection<?> scopes) {
                scopes.stream().map(String::valueOf).filter(value -> !value.isBlank())
                        .map(value -> new SimpleGrantedAuthority("SCOPE_" + value)).forEach(authorities::add);
            } else if (rawScopes instanceof String scopes) {
                for (String scope : scopes.split("\\s+")) {
                    if (!scope.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                }
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${minipay.security.issuer}") String issuer,
            @Value("${minipay.security.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null
                        && audiences.stream().anyMatch(
                        List.of("management-api", "admin-api", "consumer-api", "merchant-api", "payment-internal")::contains))));
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

    /** Onboarding starts before a dedicated merchant session exists, so either signed-in portal
     * audience may use it, while the normal merchant read/write scope is still mandatory. */
    private static AuthorizationManager<RequestAuthorizationContext> portalAudienceAndScope(String scope) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            boolean portalAudience = jwt.getToken().getAudience().stream()
                    .anyMatch(Set.of("consumer-api", "merchant-api")::contains);
            boolean hasScope = jwt.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(portalAudience && hasScope);
        };
    }

    /** Store images are available to every signed-in consumer or merchant portal user. */
    private static AuthorizationManager<RequestAuthorizationContext> authenticatedPortalAudience() {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            boolean granted = jwt.getToken().getAudience().stream()
                    .anyMatch(Set.of("consumer-api", "merchant-api")::contains);
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
                    && jwt.getAudience().contains("payment-internal")
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
}
