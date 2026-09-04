package com.minipay.agent.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.service.AgentApplicationException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

class HttpAgentBusinessGatewayTest {
    private final HttpAgentBusinessGateway gateway = new HttpAgentBusinessGateway(
            WebClient.builder(), new ObjectMapper(), "http://identity", "http://wallet",
            "http://payment", "http://commerce", Duration.ofSeconds(1));

    @Test
    void buildsBillQueryWithoutDoubleEncodingIsoInstants() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{}")
                    .build());
        });
        HttpAgentBusinessGateway gateway = new HttpAgentBusinessGateway(
                builder, new ObjectMapper(), "http://identity", "http://wallet",
                "http://payment", "http://commerce", Duration.ofSeconds(1));
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-09T03:44:55.123456Z");

        gateway.listBills("token", from, to, "EXPENSE", "TRANSFER", "SUCCEEDED", 1, 20);

        URI uri = captured.get().url();
        String decodedQuery = UriUtils.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        assertThat(decodedQuery)
                .contains("from=" + from)
                .contains("to=" + to)
                .contains("direction=EXPENSE")
                .contains("businessType=TRANSFER")
                .contains("status=SUCCEEDED");
        assertThat(uri.getRawQuery()).doesNotContain("%25");
    }

    @Test
    void distinguishesExpiredAuthenticationFromDeniedDelegation() {
        AgentApplicationException unauthorized = gateway.mapDownstream(
                WebClientResponseException.create(401, "Unauthorized", HttpHeaders.EMPTY, null, null));
        AgentApplicationException forbidden = gateway.mapDownstream(
                WebClientResponseException.create(403, "Forbidden", HttpHeaders.EMPTY, null, null));

        assertThat(unauthorized.code()).isEqualTo("AGENT_DOWNSTREAM_AUTHENTICATION_EXPIRED");
        assertThat(unauthorized.getMessage()).contains("重新登录");
        assertThat(forbidden.code()).isEqualTo("AGENT_DOWNSTREAM_ACCESS_DENIED");
        assertThat(forbidden.getMessage()).doesNotContain("重新登录");
    }

    @Test
    void preservesSafeBusinessProblemForRateLimit() {
        byte[] body = "{\"code\":\"RECIPIENT_LOOKUP_RATE_LIMITED\",\"detail\":\"查询过于频繁，请稍后重试\"}"
                .getBytes(StandardCharsets.UTF_8);
        AgentApplicationException problem = gateway.mapDownstream(
                WebClientResponseException.create(429, "Too Many Requests",
                        HttpHeaders.EMPTY, body, StandardCharsets.UTF_8));

        assertThat(problem.code()).isEqualTo("RECIPIENT_LOOKUP_RATE_LIMITED");
        assertThat(problem.getMessage()).isEqualTo("查询过于频繁，请稍后重试");
    }
}
