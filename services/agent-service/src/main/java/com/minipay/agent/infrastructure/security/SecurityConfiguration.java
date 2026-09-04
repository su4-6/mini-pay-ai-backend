package com.minipay.agent.infrastructure.security;

import java.util.List;
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
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProblemHandler problems)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/agent/**")
                        .access(audienceAndScope("consumer-api", "agent.conversation"))
                        .requestMatchers("/internal/v1/**")
                        .access(audienceAndScope("agent-internal", "agent.internal"))
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
                        List.of("consumer-api", "agent-internal")::contains))));
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
}
