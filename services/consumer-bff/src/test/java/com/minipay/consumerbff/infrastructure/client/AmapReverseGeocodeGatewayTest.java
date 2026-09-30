package com.minipay.consumerbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.consumerbff.application.error.UpstreamProblemException;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class AmapReverseGeocodeGatewayTest {
    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsFormattedAddressWithoutExposingProviderResponse() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"1\",\"regeocode\":{\"formatted_address\":\"河南省洛阳市洛龙区开元大道1号\"}}"));
        var gateway = new AmapReverseGeocodeGateway(
                WebClient.builder(), server.url("/").toString(), "server-side-key");

        StepVerifier.create(gateway.resolve(112.36537, 34.66486))
                .expectNext("河南省洛阳市洛龙区开元大道1号")
                .verifyComplete();
        assertThat(server.takeRequest().getPath())
                .contains("/v3/geocode/regeo", "location=112.36537,34.66486", "key=server-side-key");
    }

    @Test
    void mapsProviderFailureToStableProblem() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}"));
        var gateway = new AmapReverseGeocodeGateway(
                WebClient.builder(), server.url("/").toString(), "server-side-key");

        StepVerifier.create(gateway.resolve(112.36537, 34.66486))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(UpstreamProblemException.class)
                        .hasMessageContaining("LOCATION_REVERSE_GEOCODE_UNAVAILABLE"))
                .verify();
    }
}
