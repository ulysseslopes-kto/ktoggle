package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import lombok.With;

/** Content of a feature at a given revision (no technical fields), restorable as-is. */
@With
public record FeatureSnapshot(
        String key,
        String projectKey,
        ValueType valueType,
        JsonNode defaultValue,
        String description,
        String owner,
        List<String> tags,
        boolean archived,
        List<Prerequisite> prerequisites,
        Map<String, EnvironmentSettings> environments) {

    /** Snapshots stored before prerequisites existed deserialize with an empty list. */
    @JsonCreator
    public FeatureSnapshot {
        prerequisites = Prerequisite.normalize(prerequisites);
    }

    public FeatureSnapshot(String key, String projectKey, ValueType valueType, JsonNode defaultValue, String description,
                           String owner, List<String> tags, boolean archived, Map<String, EnvironmentSettings> environments) {
        this(key, projectKey, valueType, defaultValue, description, owner, tags, archived, List.of(), environments);
    }
}
