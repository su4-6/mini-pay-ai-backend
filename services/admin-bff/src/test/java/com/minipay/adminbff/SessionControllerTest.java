package com.minipay.adminbff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import reactor.core.publisher.Mono;

class SessionControllerTest {
    private final SessionController controller = new SessionController();

    @Test
    void exposesFormAndHeaderCsrfMetadataForLogout() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/csrf"));
        CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token");
        exchange.getAttributes().put(CsrfToken.class.getName(), Mono.just(token));

        ResponseEntity<Map<String, String>> entity = controller.csrf(exchange).block();
        Map<String, String> response = entity == null ? Map.of() : entity.getBody();

        assertThat(response).containsEntry("headerName", "X-CSRF-TOKEN");
        assertThat(response).containsEntry("parameterName", "_csrf");
        assertThat(response).containsEntry("token", "test-token");
        assertThat(entity).isNotNull();
        assertThat(entity.getHeaders().getCacheControl()).contains("no-store");
    }
}
