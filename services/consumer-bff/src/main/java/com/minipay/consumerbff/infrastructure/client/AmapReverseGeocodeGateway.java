package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.minipay.consumerbff.application.error.UpstreamProblemException;
import com.minipay.consumerbff.application.port.ReverseGeocodeGateway;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class AmapReverseGeocodeGateway implements ReverseGeocodeGateway {
    private final WebClient amap;
    private final String key;

    public AmapReverseGeocodeGateway(
            WebClient.Builder builder,
            @Value("${minipay.consumer-bff.amap-url:https://restapi.amap.com}") String baseUrl,
            @Value("${yshop.minipay.amap-reverse-geocode-key:${YSHOP_MINIPAY_AMAP_WEB_KEY:}}") String key) {
        this.amap = builder.baseUrl(baseUrl).build();
        this.key = key == null ? "" : key.trim();
    }

    @Override
    public Mono<String> resolve(double longitude, double latitude) {
        if (key.isBlank()) return Mono.error(unavailable());
        return amap.get()
                .uri(uri -> uri.path("/v3/geocode/regeo")
                        .queryParam("key", key)
                        .queryParam("location", longitude + "," + latitude)
                        .queryParam("extensions", "base")
                        .queryParam("output", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .map(response -> {
                    String status = response.path("status").asText();
                    String address = response.path("regeocode").path("formatted_address").asText();
                    if (!"1".equals(status) || address.isBlank()) throw unavailable();
                    return address.trim();
                })
                .onErrorMap(error -> error instanceof UpstreamProblemException ? error : unavailable());
    }

    private static UpstreamProblemException unavailable() {
        return new UpstreamProblemException(
                HttpStatus.BAD_GATEWAY, "LOCATION_REVERSE_GEOCODE_UNAVAILABLE", "地址解析暂时不可用，请重试");
    }
}
