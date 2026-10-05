package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Content of a feature at a given revision (no technical fields), restorable as-is. */
public record FeatureSnapshot(
        String key,
        String projectKey,
        ValueType valueType,
        JsonNode defaultValue,
        String description,
        String owner,
        List<String> tags,
        boolean archived,
        Map<String, EnvironmentSettings> environments) {
}
