package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Serves {@code value} to a deterministic {@code coverage} fraction (0..1) of the users matching the condition,
 * bucketed by the hash of {@code hashAttribute} (same algorithm as the GrowthBook SDKs, so buckets are sticky).
 *
 * @param seed hashing seed; null hashes with the feature key (ktoggle default). Rules imported from GrowthBook keep
 *             GrowthBook's seed (the rule id), so every user stays in or out of the rollout after the migration.
 */
public record RolloutRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                          JsonNode value, double coverage, String hashAttribute, RuleSchedule schedule,
                          List<Prerequisite> prerequisites, List<String> savedGroupsAny, List<String> savedGroupsNone,
                          String seed) implements Rule {

    @JsonCreator
    public RolloutRule {
        savedGroups = savedGroups == null ? List.of() : List.copyOf(savedGroups);
        schedule = Rule.normalize(schedule);
        prerequisites = Prerequisite.normalize(prerequisites);
        savedGroupsAny = savedGroupsAny == null ? List.of() : List.copyOf(savedGroupsAny);
        savedGroupsNone = savedGroupsNone == null ? List.of() : List.copyOf(savedGroupsNone);
    }

    public RolloutRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                       JsonNode value, double coverage, String hashAttribute, RuleSchedule schedule) {
        this(id, description, enabled, condition, savedGroups, value, coverage, hashAttribute, schedule, null, null, null, null);
    }

    public RolloutRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                       JsonNode value, double coverage, String hashAttribute) {
        this(id, description, enabled, condition, savedGroups, value, coverage, hashAttribute, null, null, null, null, null);
    }

    @Override
    public Rule withId(String newId) {
        return new RolloutRule(newId, description, enabled, condition, savedGroups, value, coverage, hashAttribute, schedule,
                prerequisites, savedGroupsAny, savedGroupsNone, seed);
    }
}
