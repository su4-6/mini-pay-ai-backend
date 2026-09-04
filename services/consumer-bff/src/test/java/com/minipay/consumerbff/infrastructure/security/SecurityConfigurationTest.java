package com.minipay.consumerbff.infrastructure.security;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

import com.minipay.consumerbff.interfaces.rest.SecuritySessionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(SecuritySessionController.class)
@Import({SecurityConfiguration.class, ReactiveSecurityProblemHandler.class, RequestIdWebFilter.class})
class SecurityConfigurationTest {
    @Autowired WebTestClient client;

    @Test
    void exposesNoStoreCsrfToken() {
        client.get().uri("/api/v1/csrf")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectBody()
                .jsonPath("$.headerName").isNotEmpty()
                .jsonPath("$.token").isNotEmpty();
    }

    @Test
    void rejectsUnsafeRequestWithoutCsrfBeforeClosedBusinessBoundary() {
        client.post().uri("/api/v1/blocked")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CSRF_TOKEN_INVALID");

        client.mutateWith(csrf()).post().uri("/api/v1/blocked")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }
}
