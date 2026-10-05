package com.ktogroup.ktoggle.commons.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * RFC 8785 (JSON Canonicalization Scheme) — the single serialization used for anything that is hashed
 * or signed (bundles, audit entries, activations, attribute digests).
 *
 * <p>The algorithm is frozen as v1: changing it would change every historical hash. Any evolution
 * must ship as a new contract version, and the golden tests in {@code CanonicalJsonTest} must never be
 * updated in place.
 *
 * <p>Numbers follow the ECMAScript double representation mandated by RFC 8785, so integers above
 * 2^53 lose precision; flag values are validated against that limit before they reach a bundle.
 */
public final class CanonicalJson {

    public static final String VERSION = "jcs-rfc8785";

    private final ObjectMapper objectMapper;

    public CanonicalJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Canonical form of any Jackson-serializable value. */
    public String canonicalize(Object value) {
        try {
            String json = value instanceof String s ? s : objectMapper.writeValueAsString(value);
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value is not serializable to JSON", e);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON canonicalization failed", e);
        }
    }

    /** True if {@code json} is already in canonical form (byte-for-byte). */
    public boolean isCanonical(String json) {
        return canonicalize(json).equals(json);
    }

    public JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }
}
