package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.With;

/**
 * A feature flag. {@code revision} increments on every change and identifies the immutable snapshot stored in
 * {@link FeatureRevision}; bundles record which revision of each feature they were compiled from.
 */
@With
public record Feature(
        String key,
        String projectKey,
        ValueType valueType,
        JsonNode defaultValue,
        String description,
        String owner,
        List<String> tags,
        boolean archived,
        List<Prerequisite> prerequisites,
        Map<String, EnvironmentSettings> environments,
        int revision,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        Long version) {

    public Feature {
        tags = tags == null ? List.of() : List.copyOf(tags);
        prerequisites = Prerequisite.normalize(prerequisites);
        environments = environments == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(environments));
    }

    public Feature(String key, String projectKey, ValueType valueType, JsonNode defaultValue, String description, String owner,
                   List<String> tags, boolean archived, Map<String, EnvironmentSettings> environments, int revision,
                   Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Long version) {
        this(key, projectKey, valueType, defaultValue, description, owner, tags, archived, List.of(), environments, revision,
                createdAt, createdBy, updatedAt, updatedBy, version);
    }

    public EnvironmentSettings environment(String environmentKey) {
        return environments.getOrDefault(environmentKey, EnvironmentSettings.DISABLED);
    }

    /** The versionable content of the feature (what a revision snapshot captures). */
    public FeatureSnapshot snapshot() {
        return new FeatureSnapshot(key, projectKey, valueType, defaultValue, description, owner, tags, archived, prerequisites,
                environments);
    }
}
