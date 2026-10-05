package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A/B experiment (GrowthBook "experiment" rule): users matching the condition are bucketed by the hash of
 * {@code hashAttribute}; a {@code coverage} fraction of them enters the experiment and gets one of the variations,
 * split by weight. Assignment is deterministic and sticky, and the SDK reports each exposure through its tracking
 * callback with {@code trackingKey} and the variation key. Analysis happens in the analytics tool fed by that callback.
 *
 * @param trackingKey experiment key sent to the tracking callback (stable identifier of the experiment)
 * @param hashVersion GrowthBook hashing algorithm version (2 for new experiments)
 * @param seed        optional hashing seed; when absent SDKs use the tracking key
 */
public record ExperimentRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                             String trackingKey, String hashAttribute, double coverage, List<Variation> variations,
                             int hashVersion, String seed, RuleSchedule schedule, List<Prerequisite> prerequisites)
        implements Rule {

    public static final int DEFAULT_HASH_VERSION = 2;

    @JsonCreator
    public ExperimentRule {
        savedGroups = savedGroups == null ? List.of() : List.copyOf(savedGroups);
        variations = variations == null ? List.of() : List.copyOf(variations);
        hashVersion = hashVersion == 0 ? DEFAULT_HASH_VERSION : hashVersion;
        schedule = Rule.normalize(schedule);
        prerequisites = Prerequisite.normalize(prerequisites);
    }

    public ExperimentRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                          String trackingKey, String hashAttribute, double coverage, List<Variation> variations,
                          int hashVersion, String seed) {
        this(id, description, enabled, condition, savedGroups, trackingKey, hashAttribute, coverage, variations, hashVersion,
                seed, null, null);
    }

    /** The control (first variation) value — used where a single representative value is needed. */
    @Override
    public JsonNode value() {
        return variations.isEmpty() ? null : variations.getFirst().value();
    }

    @Override
    public Rule withId(String newId) {
        return new ExperimentRule(newId, description, enabled, condition, savedGroups, trackingKey, hashAttribute, coverage,
                variations, hashVersion, seed, schedule, prerequisites);
    }

    /**
     * @param key    stable variation key reported to analytics ("0", "1"... or e.g. "control", "treatment")
     * @param weight share of the experiment traffic (all weights sum to 1)
     */
    public record Variation(String key, String name, JsonNode value, double weight) {
    }
}
