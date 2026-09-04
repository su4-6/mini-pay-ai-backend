package com.minipay.adminbff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class PortalSwitchControllerTest {
    @Test
    void legacyBffLoginRedirectsToAdminWebLogin() {
        var controller = new PortalSwitchController("http://localhost:8081", "http://localhost:8002/");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login").build());

        controller.login(exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation())
                .hasToString("http://localhost:8002/login");
    }
}
