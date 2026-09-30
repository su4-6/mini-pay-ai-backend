package com.minipay.managementbff.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.server.MockWebSession;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

class MerchantSessionControllerTest {
    @Test
    void productionConstructorStartsInSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                    "minipay.identity-internal-url", "http://identity",
                    "minipay.merchant-oauth-client-id", "merchant-web",
                    "minipay.merchant-oauth-redirect-uri", "https://merchant/callback")));
            context.register(MerchantSessionController.class);
            context.refresh();

            assertThat(context.getBean(MerchantSessionController.class)).isNotNull();
        }
    }

    @Test
    void sessionRefreshesPhoneFromIdentityInsteadOfKeepingLoginSnapshot() {
        AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> request =
                new AtomicReference<>();
        WebClient identity = WebClient.builder().exchangeFunction(value -> {
            request.set(value);
            return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"phone\":\"15036084035\"}").build());
        }).build();
        MerchantSessionController controller = new MerchantSessionController(identity, "merchant-web", "https://merchant/callback");
        MockWebSession session = new MockWebSession();
        session.getAttributes().put("merchant.access-token", "access-token");
        session.getAttributes().put("merchant.access-token-expires-at", Instant.now().plusSeconds(300));
        session.getAttributes().put("merchant.login-phone", "13800138000");
        session.getAttributes().put("merchant.password-configured", true);

        MerchantSessionController.MerchantSessionResponse response = controller.session(session).block();

        assertThat(response.phone()).isEqualTo("15036084035");
        assertThat((String) session.getAttribute("merchant.login-phone")).isEqualTo("15036084035");
        assertThat(request.get().url().getPath()).isEqualTo("/api/v1/auth/merchant/account");
        assertThat(request.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer access-token");
    }
}
