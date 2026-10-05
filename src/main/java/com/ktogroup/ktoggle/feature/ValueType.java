package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;

/** Type of a feature value. Every value (default, force) is validated against it before it can be published. */
public enum ValueType {
    BOOLEAN,
    STRING,
    NUMBER,
    JSON;

    /** Largest integer exactly representable in the RFC 8785 (IEEE 754 double) number format. */
    private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_991d;

    public boolean accepts(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        return switch (this) {
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isTextual();
            case NUMBER -> value.isNumber() && isSafeNumber(value);
            case JSON -> true;
        };
    }

    private static boolean isSafeNumber(JsonNode value) {
        double d = value.asDouble();
        return Double.isFinite(d) && (!value.isIntegralNumber() || Math.abs(d) <= MAX_SAFE_INTEGER);
    }
}
