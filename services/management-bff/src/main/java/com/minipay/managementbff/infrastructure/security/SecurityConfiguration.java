package com.minipay.managementbff.infrastructure.security;

import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {

    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clients,
            ServerOAuth2AuthorizedClientRepository authorizedClients) {
        ReactiveOAuth2AuthorizedClientProvider provider =
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .build();
        DefaultReactiveOAuth2AuthorizedClientManager manager =
                new DefaultReactiveOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveClientRegistrationRepository clients,
            OAuthAuthorizationRevocationLogoutHandler revocationLogout,
            IdentityLogoutSuccessHandler logoutSuccess,
            ReactiveSecurityProblemHandler problems,
            @Value("${minipay.ops-web-url}") String opsWebUrl) {
        RedirectServerAuthenticationSuccessHandler loginSuccess =
                new RedirectServerAuthenticationSuccessHandler(opsWebUrl);
        SecurityContextServerLogoutHandler securityContextLogout =
                new SecurityContextServerLogoutHandler();
        ServerLogoutHandler logoutHandler = (exchange, authentication) -> revocationLogout
                .logout(exchange, authentication)
                .then(securityContextLogout.logout(exchange, authentication));

        DefaultServerOAuth2AuthorizationRequestResolver authorizationRequestResolver =
                new DefaultServerOAuth2AuthorizationRequestResolver(clients);
        authorizationRequestResolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new WebSessionServerCsrfTokenRepository())
                        .requireCsrfProtectionMatcher(new org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher(
                                org.springframework.security.web.server.csrf.CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                                new org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher(
                                        org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers("/api/v1/merchant-auth/**"))))
                        .accessDeniedHandler(problems))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(
                                "/actuator/health/**", "/actuator/info",
                                "/api/v1/session", "/api/v1/csrf", "/api/v1/ops-session", "/api/v1/ops-csrf",
                                "/api/v1/merchant-auth/**", "/api/v1/merchant-session",
                                "/api/v1/merchant-gateway/**",
                                "/api/v1/merchant-image-uploads",
                                "/api/v1/merchant-wallet", "/api/v1/merchant-wallet/**",
                                "/oauth2/**", "/login/**", "/switch-login")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/ops/dashboard")
                        .access(platformAdminWithScope("ops.dashboard.read"))
                        .pathMatchers(HttpMethod.GET, "/api/v1/ops/merchants/**")
                        .access(platformAdminWithScope("ops.merchant.read"))
                        .pathMatchers("/api/v1/ops/merchants/**")
                        .access(platformAdminWithScope("ops.merchant.write"))
                        .pathMatchers(HttpMethod.GET, "/api/v1/ops/merchant-applies/**")
                        .access(platformAdminWithScope("ops.merchant.read"))
                        .pathMatchers("/api/v1/ops/merchant-applies/**")
                        .access(platformAdminWithScope("ops.merchant.write"))
                        .pathMatchers(HttpMethod.GET, "/api/v1/ops/application-applies/**")
                        .access(platformAdminWithScope("ops.application.read"))
                        .pathMatchers("/api/v1/ops/application-applies/**")
                        .access(platformAdminWithScope("ops.application.write"))
                        .pathMatchers("/api/v1/ops/image-uploads/**")
                        .access(platformAdminWithScope("ops.application.write"))
                        .pathMatchers("/api/v1/ops-image-uploads")
                        .access(platformAdminWithScope("ops.application.write"))
                        .pathMatchers("/api/v1/ops/image-read-urls/**")
                        .access(platformAdminWithScope("ops.application.write"))
                        .pathMatchers(HttpMethod.GET, "/api/v1/ops/applications/**")
                        .access(platformAdminWithScope("ops.application.read"))
                        .pathMatchers("/api/v1/ops/applications/**")
                        .access(platformAdminWithScope("ops.application.write"))
                        .anyExchange().access((authentication, context) -> authentication
                                .map(value -> new AuthorizationDecision(
                                        value instanceof OAuth2AuthenticationToken token
                                                && token.getPrincipal() instanceof OidcUser user
                                                && user.getClaimAsStringList("roles") != null
                                                && user.getClaimAsStringList("roles").contains("platform_admin")))
                                .defaultIfEmpty(new AuthorizationDecision(false))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2Login(oauth -> oauth
                        .authorizationRequestResolver(authorizationRequestResolver)
                        .authenticationSuccessHandler(loginSuccess))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutHandler(logoutHandler)
                        .logoutSuccessHandler(logoutSuccess))
                .build();
    }

    private static org.springframework.security.authorization.ReactiveAuthorizationManager<
            org.springframework.security.web.server.authorization.AuthorizationContext>
            platformAdminWithScope(String scope) {
        return (authentication, context) -> authentication
                .map(value -> {
                    boolean platformAdmin = value instanceof OAuth2AuthenticationToken token
                            && token.getPrincipal() instanceof OidcUser user
                            && user.getClaimAsStringList("roles") != null
                            && user.getClaimAsStringList("roles").contains("platform_admin");
                    boolean hasScope = value.getAuthorities().stream().anyMatch(
                            authority -> authority.getAuthority().equals("SCOPE_" + scope));
                    return new AuthorizationDecision(platformAdmin && hasScope);
                })
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

}
