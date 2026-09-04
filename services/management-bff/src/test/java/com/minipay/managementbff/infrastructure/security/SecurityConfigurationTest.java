package com.minipay.managementbff.infrastructure.security;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;

import com.minipay.managementbff.interfaces.rest.SessionController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(SessionController.class)
@Import({SecurityConfiguration.class, ReactiveSecurityProblemHandler.class, RequestIdWebFilter.class})
@TestPropertySource(properties = "minipay.ops-web-url=http://localhost:8000/")
class SecurityConfigurationTest {
    @Autowired WebTestClient client;
    @MockitoBean ReactiveClientRegistrationRepository clients;
    @MockitoBean OAuthAuthorizationRevocationLogoutHandler revocationLogout;
    @MockitoBean IdentityLogoutSuccessHandler logoutSuccess;

    @Test
    void logoutWithoutCsrfReturnsProblemDetails() {
        client.post().uri("/logout")
                .header("X-Request-Id", "logout-request")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CSRF_TOKEN_INVALID")
                .jsonPath("$.requestId").isEqualTo("logout-request");
    }

    @Test
    void dashboardRequiresItsDedicatedScope() {
        client.mutateWith(mockOidcLogin().idToken(token -> token.claim(
                        "roles", List.of("platform_admin"))))
                .get().uri("/api/v1/ops/dashboard")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.dashboard.read")))
                .get().uri("/api/v1/ops/dashboard")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void merchantReadScopeCannotWriteAndWritesRequireCsrf() {
        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.merchant.read")))
                .mutateWith(csrf())
                .post().uri("/api/v1/ops/merchants")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.merchant.write")))
                .post().uri("/api/v1/ops/merchants")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void merchantApplyReadScopeCannotWrite() {
        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.merchant.read")))
                .mutateWith(csrf())
                .post().uri("/api/v1/ops/merchant-applies/1/reject")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.merchant.write")))
                .get().uri("/api/v1/ops/merchant-applies")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void applicationApplyReadScopeCannotWrite() {
        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.application.read")))
                .mutateWith(csrf())
                .post().uri("/api/v1/ops/application-applies/1/approve")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.application.write")))
                .get().uri("/api/v1/ops/application-applies")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void applicationReadAndWriteScopesAreSeparated() {
        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.application.read")))
                .mutateWith(csrf())
                .post().uri("/api/v1/ops/applications")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockOidcLogin()
                        .idToken(token -> token.claim("roles", List.of("platform_admin")))
                        .authorities(authority("SCOPE_ops.application.write")))
                .post().uri("/api/v1/ops/applications")
                .exchange()
                .expectStatus().isForbidden();
    }

    private static org.springframework.security.core.GrantedAuthority authority(String value) {
        return new org.springframework.security.core.authority.SimpleGrantedAuthority(value);
    }
}
