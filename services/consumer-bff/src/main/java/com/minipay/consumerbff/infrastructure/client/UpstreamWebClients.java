package com.minipay.consumerbff.infrastructure.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Builds one WebClient per upstream host. No upstream URL has a non-local production default. */
public final class UpstreamWebClients {

    private static final int MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024;

    private UpstreamWebClients() {
    }

    public static WebClient json(String baseUrl) {
        return builder(baseUrl, Duration.ofSeconds(10)).build();
    }

    /**
     * The AI run stream can stay open for a long time, so the read timeout is disabled and a
     * dedicated connector keeps the idle SSE connection alive.
     */
    public static WebClient streaming(String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofMinutes(30))
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(30, TimeUnit.MINUTES)));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies())
                .build();
    }

    private static WebClient.Builder builder(String baseUrl, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(responseTimeout)
                .doOnConnected(connection -> connection.addHandlerLast(new ReadTimeoutHandler(
                        responseTimeout.toMillis(), TimeUnit.MILLISECONDS)));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies());
    }

    private static ExchangeStrategies strategies() {
        return ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }
}
