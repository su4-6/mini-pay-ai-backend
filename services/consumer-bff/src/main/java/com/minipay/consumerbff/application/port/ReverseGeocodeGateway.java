package com.minipay.consumerbff.application.port;

import reactor.core.publisher.Mono;

public interface ReverseGeocodeGateway {
    Mono<String> resolve(double longitude, double latitude);
}
