package com.minipay.consumerbff.interfaces.rest;

import java.util.UUID;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/** Extracts the propagated request id from the exchange populated by {@code RequestIdWebFilter}. */
final class RequestIds {

    private RequestIds() {
    }

    static String of(ServerWebExchange exchange) {
        return com.minipay.consumerbff.infrastructure.security.RequestIdWebFilter.get(exchange);
    }

    static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ServerWebInputException(field + " must be a UUID");
        }
    }
}
