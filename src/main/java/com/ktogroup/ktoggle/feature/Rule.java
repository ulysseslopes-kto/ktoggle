package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * An override rule evaluated top to bottom; the first matching rule wins, otherwise the default value is served.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ForceRule.class, name = "force"),
        @JsonSubTypes.Type(value = RolloutRule.class, name = "rollout"),
        @JsonSubTypes.Type(value = ExperimentRule.class, name = "experiment")
})
public sealed interface Rule permits ForceRule, RolloutRule, ExperimentRule {

    String id();

    String description();

    boolean enabled();

    /** Targeting condition (MongoDB-like); null or {} matches everyone. */
    JsonNode condition();

    /** Keys of saved groups the user must belong to (AND), inlined into the condition at compile time. */
    List<String> savedGroups();

    /** Value served when the rule applies (for experiments: the control variation). */
    JsonNode value();

    Rule withId(String newId);

    /** Features this rule depends on (all must pass); when unmet, the rule is skipped. */
    List<Prerequisite> prerequisites();

    /** Optional time window; null when the rule is always live (while enabled). */
    RuleSchedule schedule();

    /** Whether the rule is part of the payload at {@code instant}: enabled and inside its schedule. */
    default boolean liveAt(Instant instant) {
        return enabled() && (schedule() == null || schedule().activeAt(instant));
    }

    /** An empty schedule is the same as no schedule; keeps stored JSON and diffs canonical. */
    static RuleSchedule normalize(RuleSchedule schedule) {
        return schedule == null || schedule.isEmpty() ? null : schedule;
    }
}
