package com.ktogroup.ktoggle.commons.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSONB &lt;-&gt; {@link JsonNode} conversion for JdbcTemplate-based adapters. Values are bound as text and
 * cast in SQL ({@code CAST(? AS jsonb)}), keeping the code free of driver-specific types.
 */
public final class JsonColumns {

    private JsonColumns() {
    }

    public static String write(ObjectMapper objectMapper, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize JSONB value", e);
        }
    }

    public static JsonNode read(ObjectMapper objectMapper, String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted JSONB value", e);
        }
    }
}
