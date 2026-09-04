package com.minipay.managementbff.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class MerchantImageUploadControllerTest {
    private DisposableServer upstream;

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.disposeNow();
        }
    }

    @Test
    void uploadsThroughBffInsteadOfReturningBrowserToOss() {
        byte[] image = new byte[]{1, 2, 3, 4};
        AtomicReference<String> grantRequest = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicInteger port = new AtomicInteger();
        upstream = HttpServer.create().port(0).route(routes -> routes
                        .post("/api/v1/merchant/image-uploads", (request, response) ->
                                request.receive().aggregate().asString().flatMap(body -> {
                                    grantRequest.set(body);
                                    String grant = "{\"uploadUrl\":\"http://localhost:" + port.get()
                                            + "/oss\",\"objectKey\":\"merchants/images/test.png\","
                                            + "\"requiredHeaders\":{\"Content-Type\":\"image/png\"},"
                                            + "\"expiresAt\":\"2026-08-08T12:00:00Z\"}";
                                    return response.header("Content-Type", "application/json")
                                            .sendString(Mono.just(grant)).then();
                                }))
                        .put("/oss", (request, response) ->
                                request.receive().aggregate().asByteArray().flatMap(body -> {
                                    uploaded.set(body);
                                    return response.status(200).send().then();
                                })))
                .bindNow();
        port.set(upstream.port());

        WebSession session = mock(WebSession.class);
        when(session.getAttribute("merchant.access-token")).thenReturn("merchant-token");
        when(session.getAttribute("merchant.access-token-expires-at"))
                .thenReturn(Instant.now().plusSeconds(300));
        when(session.getAttributes()).thenReturn(new java.util.HashMap<>());
        MerchantImageUploadController controller =
                new MerchantImageUploadController("http://localhost:" + port.get(), "");

        MerchantImageUploadController.UploadResponse result = controller.upload(
                "shop.png", "image/png", Mono.just(image), session).block();

        assertThat(result).isNotNull();
        assertThat(result.objectKey()).isEqualTo("merchants/images/test.png");
        assertThat(uploaded.get()).containsExactly(image);
        assertThat(grantRequest.get())
                .contains("\"fileName\":\"shop.png\"")
                .contains("\"sizeBytes\":4")
                .contains("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a");
    }
}
