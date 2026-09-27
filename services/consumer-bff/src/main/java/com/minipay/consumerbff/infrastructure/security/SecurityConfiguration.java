package com.minipay.consumerbff.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/**
 * Consumer H5 security boundary.
 *
 * <ul>
 *   <li>The browser holds only the {@code __Host-minipay-consumer} session cookie; no token ever
 *       reaches JavaScript.
 *   <li>CSRF protection is mandatory for every state-changing request except the two login steps and
 *       the CSRF handshake itself.
 *   <li>Everything under {@code /api/**} outside the public session surface requires an
 *       authenticated WebSession.
 * </ul>
 */
@Configuration
public class SecurityConfiguration {

    /** Unauthenticated by design: the client cannot present a session before signing in. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/actuator/info",
            "/api/v1/csrf",
            "/api/v1/session/sms",
            "/api/v1/session/callback",
    };

    /**
     * Paths that must answer without an authenticated session: the browser loads
     * {@code GET /api/v1/session} on every page to decide between the login screen and the app, and
     * signing out must work even after the server-side session already expired.
     */
    private static final String[] PUBLIC_SESSION_SURFACE = {
            "/api/v1/session",
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ReactiveSecurityProblemHandler problems) {
        AuthenticationWebFilter sessionAuthentication = new AuthenticationWebFilter(
                (org.springframework.security.authentication.ReactiveAuthenticationManager)
                        authentication -> Mono.just(authentication));
        sessionAuthentication.setServerAuthenticationConverter(
                new ConsumerSessionAuthenticationConverter());
        sessionAuthentication.setSecurityContextRepository(
                NoOpServerSecurityContextRepository.getInstance());
        sessionAuthentication.setRequiresAuthenticationMatcher(
                ServerWebExchangeMatchers.pathMatchers("/api/**"));

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new WebSessionServerCsrfTokenRepository())
                        // Spring Security 6 defaults to the XOR (BREACH-masking) request handler,
                        // which makes the token exposed through the exchange attribute differ from
                        // the value stored in the session. A browser client echoing that token in
                        // the X-CSRF-TOKEN header would then be rejected on every write request.
                        // The plain attribute handler exposes the raw token, which is the
                        // documented choice for header-based single-page clients.
                        .csrfTokenRequestHandler(
                                new org.springframework.security.web.server.csrf
                                        .ServerCsrfTokenRequestAttributeHandler())
                        .accessDeniedHandler(problems))
                .authorizeExchange(exchange -> exchange
                        // The session surface answers unauthenticated callers but every
                        // state-changing verb on it still passes the CSRF filter above.
                        .pathMatchers(PUBLIC_SESSION_SURFACE).permitAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers("/api/**").authenticated()
                        // Nothing else is exposed: the BFF has no static or template surface.
                        .anyExchange().denyAll())
                .addFilterAt(sessionAuthentication, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.disable())
                .build();
    }
}
