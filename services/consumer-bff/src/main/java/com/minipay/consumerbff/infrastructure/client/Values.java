package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;

/** Null-safe readers for upstream JSON documents. */
final class Values {

    private Values() {
    }

    static String text(JsonNode document, String field) {
        JsonNode value = document == null ? null : document.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    static boolean bool(JsonNode document, String field) {
        JsonNode value = document == null ? null : document.get(field);
        return value != null && value.asBoolean(false);
    }

    static long number(JsonNode document, String field, long fallback) {
        JsonNode value = document == null ? null : document.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }

    static Instant instant(JsonNode document, String field) {
        String value = text(document, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static Optional<JsonNode> node(JsonNode document, String field) {
        JsonNode value = document == null ? null : document.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }
}
