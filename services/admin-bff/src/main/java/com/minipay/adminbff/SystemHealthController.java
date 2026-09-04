package com.minipay.adminbff;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Returns only coarse dependency health; no actuator details cross the admin boundary. */
@RestController
@RequestMapping("/api/v1/admin/system-health")
public class SystemHealthController {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
    private final WebClient web;
    private final List<ServiceTarget> targets;

    public SystemHealthController(
            WebClient.Builder web,
            @Value("${minipay.identity-internal-url}") String identity,
            @Value("${minipay.payment-internal-url}") String payment,
            @Value("${minipay.wallet-internal-url}") String wallet) {
        this.web = web.build();
        this.targets = List.of(
                new ServiceTarget("identity", "身份服务", identity),
                new ServiceTarget("payment", "支付服务", payment),
                new ServiceTarget("wallet", "钱包服务", wallet));
    }

    @GetMapping
    public Mono<SystemHealthResponse> health() {
        return Flux.fromIterable(targets)
                .flatMap(this::probe)
                .collectList()
                .map(services -> new SystemHealthResponse(
                        services.stream().allMatch(service -> service.status().equals("UP")) ? "UP" : "DEGRADED",
                        services));
    }

    private Mono<ServiceHealth> probe(ServiceTarget target) {
        long startedAt = System.nanoTime();
        return web.get()
                .uri(target.baseUrl() + "/actuator/health/readiness")
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.just(health(target, "DOWN", startedAt));
                    }
                    return response.bodyToMono(Map.class)
                            .map(body -> health(
                                    target,
                                    "UP".equalsIgnoreCase(String.valueOf(body.get("status"))) ? "UP" : "DOWN",
                                    startedAt))
                            .defaultIfEmpty(health(target, "DOWN", startedAt));
                })
                .timeout(PROBE_TIMEOUT)
                .onErrorReturn(health(target, "DOWN", startedAt));
    }

    private static ServiceHealth health(ServiceTarget target, String status, long startedAt) {
        return new ServiceHealth(target.code(), target.name(), status, elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private record ServiceTarget(String code, String name, String baseUrl) { }
    public record ServiceHealth(String code, String name, String status, long latencyMs) { }
    public record SystemHealthResponse(String status, List<ServiceHealth> services) { }
}
