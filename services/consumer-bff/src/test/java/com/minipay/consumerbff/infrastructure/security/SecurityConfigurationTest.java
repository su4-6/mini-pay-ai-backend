package com.minipay.consumerbff.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import com.minipay.consumerbff.domain.identity.SmsChallenge;
import com.minipay.consumerbff.domain.session.ConsumerSession;
import com.minipay.consumerbff.interfaces.rest.AiProxyController;
import com.minipay.consumerbff.interfaces.rest.ConsumerProblemAdvice;
import com.minipay.consumerbff.interfaces.rest.ConsumerProxyController;
import com.minipay.consumerbff.interfaces.rest.SessionController;
import com.minipay.consumerbff.interfaces.rest.TransferProxyController;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = {
        SessionController.class,
        ConsumerProxyController.class,
        TransferProxyController.class,
        AiProxyController.class})
@Import({
        SecurityConfiguration.class,
        ReactiveSecurityProblemHandler.class,
        RequestIdWebFilter.class,
        ConsumerProblemAdvice.class})
class SecurityConfigurationTest {

    @Autowired
    WebTestClient client;

    @MockBean
    ConsumerSessionService sessions;

    @Test
    void exposesNoStoreCsrfToken() {
        client.get().uri("/api/v1/csrf")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectBody()
                .jsonPath("$.headerName").isNotEmpty()
                .jsonPath("$.parameterName").isNotEmpty()
                .jsonPath("$.token").isNotEmpty();
    }

    @Test
    void rejectsStateChangingRequestWithoutCsrfBeforeTheSessionBoundary() {
        client.post().uri("/api/v1/session")
                .header("Content-Type", "application/json")
                .bodyValue("{\"mobile\":\"13800138000\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CSRF_TOKEN_INVALID")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void rejectsUnauthenticatedReadsUnderApiWithoutASession() {
        client.get().uri("/api/v1/wallet")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void loginStepsArePublicButStillCsrfProtected() {
        when(sessions.requestSmsCode(any(), any(), any()))
                .thenReturn(Mono.just(new SmsChallenge(
                        "challenge-1", Instant.parse("2030-01-01T00:05:00Z"), null)));
        when(sessions.logout(any(), any())).thenReturn(Mono.empty());

        client.mutateWith(csrf()).post().uri("/api/v1/session/sms")
                .header("Content-Type", "application/json")
                .bodyValue("{\"mobile\":\"13800138000\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.challengeId").isEqualTo("challenge-1")
                // The demo code is only forwarded when Identity actually returns one.
                .jsonPath("$.demoCode").doesNotExist();

        client.post().uri("/api/v1/session/sms")
                .header("Content-Type", "application/json")
                .bodyValue("{\"mobile\":\"13800138000\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void logoutWithoutCsrfIsRejectedBeforeTheSessionIsTouched() {
        client.delete().uri("/api/v1/session")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void logoutIsAcceptedWithoutAnAuthenticatedSessionSoExpiredSessionsCanSignOut() {
        client.mutateWith(csrf()).delete().uri("/api/v1/session")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void sessionSummaryReportsUnauthenticatedWithoutASession() {
        when(sessions.currentSession(any())).thenReturn(Mono.empty());

        client.get().uri("/api/v1/session")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(false);
    }

    @Test
    void sessionSummaryNeverEchoesCredentials() {
        when(sessions.currentSession(any())).thenReturn(Mono.just(new ConsumerSession(
                UUID.fromString("0198f200-0000-7000-8000-000000000001"),
                "138****8000", "绫崇伒鐢ㄦ埛", true, false, "VERIFIED", true)));

        client.get().uri("/api/v1/session")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("0198f200-0000-7000-8000-000000000001")
                .jsonPath("$.phone").isEqualTo("138****8000")
                .jsonPath("$.accessToken").doesNotExist()
                .jsonPath("$.refreshToken").doesNotExist()
                .jsonPath("$.paymentPassword").doesNotExist()
                .jsonPath("$.paymentPasswordSet").doesNotExist();
    }

    @Test
    void unknownApiPathsRequireAuthentication() {
        client.get().uri("/api/v2/anything")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

