package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** The {@code headers} jsonb column: a flat object of text values. */
final class HeadersJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TEXT_MAP = new TypeReference<>() {
    };

    private HeadersJson() {
    }

    static String write(Map<String, String> headers) {
        try {
            return MAPPER.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox headers could not be written as JSON", e);
        }
    }

    static Map<String, String> read(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, TEXT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox headers column does not hold a JSON object of text values", e);
        }
    }
}
