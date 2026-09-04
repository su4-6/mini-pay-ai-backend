package com.minipay.managementbff.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import reactor.core.publisher.Mono;

class SessionControllerTest {
    private final SessionController controller = new SessionController();

    @Test
    void exposesRolesAndScopePermissionsWithoutExposingTokens() {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "not-returned-to-browser",
                now,
                now.plusSeconds(600),
                Map.of(
                        "sub", "019fb3d0-0000-7000-8000-000000000001",
                        "user_id", "019fb3d0-0000-7000-8000-000000000001",
                        "display_name", "演示管理员",
                        "roles", List.of("platform_admin")));
        List<org.springframework.security.core.GrantedAuthority> authorities = List.of(
                new OidcUserAuthority(idToken),
                new SimpleGrantedAuthority("SCOPE_ops.portal"),
                new SimpleGrantedAuthority("SCOPE_ops.audit.read"),
                new SimpleGrantedAuthority("SCOPE_ops.dashboard.read"),
                new SimpleGrantedAuthority("SCOPE_ops.merchant.read"),
                new SimpleGrantedAuthority("SCOPE_ops.merchant.write"),
                new SimpleGrantedAuthority("ROLE_platform_admin"));
        DefaultOidcUser user = new DefaultOidcUser(authorities, idToken, "sub");
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(user, authorities, "minipay-ops");

        SessionController.SessionResponse response =
                controller.session(Mono.just(authentication)).block();

        assertThat(response).isNotNull();
        assertThat(response.authenticated()).isTrue();
        assertThat(response.admin().roles()).containsExactly("platform_admin");
        assertThat(response.admin().permissions())
                .containsExactly(
                        "ops.audit.read", "ops.dashboard.read", "ops.merchant.read",
                        "ops.merchant.write", "ops.portal");
        assertThat(response.toString()).doesNotContain("not-returned-to-browser");
    }

    @Test
    void exposesCsrfMetadataWithoutUsingCookiesOrBrowserStorage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/csrf"));
        CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token");
        exchange.getAttributes().put(CsrfToken.class.getName(), Mono.just(token));

        org.springframework.http.ResponseEntity<Map<String, String>> entity =
                controller.csrf(exchange).block();
        Map<String, String> response = entity == null ? Map.of() : entity.getBody();

        assertThat(response).containsEntry("headerName", "X-CSRF-TOKEN");
        assertThat(response).containsEntry("parameterName", "_csrf");
        assertThat(response).containsEntry("token", "test-token");
        assertThat(entity.getHeaders().getCacheControl()).contains("no-store");
    }
}
