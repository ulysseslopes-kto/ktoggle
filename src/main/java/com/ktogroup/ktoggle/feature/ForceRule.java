package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Serves {@code value} to everyone matching the condition. */
public record ForceRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                        JsonNode value, RuleSchedule schedule, List<Prerequisite> prerequisites,
                        List<String> savedGroupsAny, List<String> savedGroupsNone) implements Rule {

    @JsonCreator
    public ForceRule {
        savedGroups = savedGroups == null ? List.of() : List.copyOf(savedGroups);
        schedule = Rule.normalize(schedule);
        prerequisites = Prerequisite.normalize(prerequisites);
        savedGroupsAny = savedGroupsAny == null ? List.of() : List.copyOf(savedGroupsAny);
        savedGroupsNone = savedGroupsNone == null ? List.of() : List.copyOf(savedGroupsNone);
    }

    public ForceRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                     JsonNode value, RuleSchedule schedule) {
        this(id, description, enabled, condition, savedGroups, value, schedule, null, null, null);
    }

    public ForceRule(String id, String description, boolean enabled, JsonNode condition, List<String> savedGroups,
                     JsonNode value) {
        this(id, description, enabled, condition, savedGroups, value, null, null, null, null);
    }

    @Override
    public Rule withId(String newId) {
        return new ForceRule(newId, description, enabled, condition, savedGroups, value, schedule, prerequisites, savedGroupsAny,
                savedGroupsNone);
    }
}
