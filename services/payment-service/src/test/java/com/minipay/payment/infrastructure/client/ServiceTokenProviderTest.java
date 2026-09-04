package com.minipay.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceTokenProviderTest {
    @Test
    void requestsAndCachesWalletInternalTokenWithRequiredScopes() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"access_token\":\"wallet-service-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ServiceTokenProvider provider = new ServiceTokenProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/token",
                    "payment-wallet-client",
                    "payment-wallet-secret");

            assertThat(provider.walletToken()).isEqualTo("wallet-service-token");
            assertThat(provider.walletToken()).isEqualTo("wallet-service-token");
            assertThat(requests).hasValue(1);
            assertThat(authorization).hasValue("Basic " + Base64.getEncoder().encodeToString(
                    "payment-wallet-client:payment-wallet-secret".getBytes(StandardCharsets.UTF_8)));
            assertThat(requestBody.get())
                    .contains("grant_type=client_credentials")
                    .contains("scope=wallet.account.resolve+wallet.posting.write+wallet.tcc");
        } finally {
            server.stop(0);
        }
    }
}
