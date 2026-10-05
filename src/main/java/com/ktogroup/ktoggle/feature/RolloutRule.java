package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Serves {@code value} to a deterministic {@code coverage} fraction (0..1) of the users matching the condition,
 * bucketed by the hash of {@code hashAttribute} (same algorithm as the GrowthBook SDKs, so buckets are sticky).
 */
public record RolloutRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                          JsonNode value, double coverage, String hashAttribute, RuleSchedule schedule) implements Rule {

    public RolloutRule {
        savedGroups = savedGroups == null ? List.of() : List.copyOf(savedGroups);
        schedule = Rule.normalize(schedule);
    }

    public RolloutRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                       JsonNode value, double coverage, String hashAttribute) {
        this(id, description, enabled, condition, savedGroups, value, coverage, hashAttribute, null);
    }

    @Override
    public Rule withId(String newId) {
        return new RolloutRule(newId, description, enabled, condition, savedGroups, value, coverage, hashAttribute, schedule);
    }
}
