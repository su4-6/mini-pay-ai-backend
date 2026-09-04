package com.minipay.adminbff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SystemHealthControllerTest {

    @Test
    void reportsEachDependencyWithoutExposingActuatorDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            byte[] response = "{\"status\":\"UP\",\"details\":{\"secret\":true}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String healthyBase = "http://127.0.0.1:" + server.getAddress().getPort();
            SystemHealthController controller = new SystemHealthController(
                    WebClient.builder(), healthyBase, healthyBase, "http://127.0.0.1:1");

            SystemHealthController.SystemHealthResponse response = controller.health()
                    .block(Duration.ofSeconds(5));

            assertNotNull(response);
            assertEquals("DEGRADED", response.status());
            Map<String, String> statuses = response.services().stream()
                    .collect(Collectors.toMap(SystemHealthController.ServiceHealth::code,
                            SystemHealthController.ServiceHealth::status));
            assertEquals("UP", statuses.get("identity"));
            assertEquals("UP", statuses.get("payment"));
            assertEquals("DOWN", statuses.get("wallet"));
        } finally {
            server.stop(0);
        }
    }
}
