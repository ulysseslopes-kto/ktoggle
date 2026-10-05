package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * An override rule evaluated top to bottom; the first matching rule wins, otherwise the default value is served.
 * Experiment rules will join this hierarchy in phase 2.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ForceRule.class, name = "force"),
        @JsonSubTypes.Type(value = RolloutRule.class, name = "rollout")
})
public sealed interface Rule permits ForceRule, RolloutRule {

    String id();

    String description();

    boolean enabled();

    /** Targeting condition (MongoDB-like); null or {} matches everyone. */
    JsonNode condition();

    /** Keys of saved groups the user must belong to (AND), inlined into the condition at compile time. */
    List<String> savedGroups();

    /** Value served when the rule applies. */
    JsonNode value();

    Rule withId(String newId);
}
