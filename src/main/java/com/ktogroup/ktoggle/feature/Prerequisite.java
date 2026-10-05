package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Dependency on another feature (GrowthBook "prerequisite"): {@code condition} is evaluated against
 * {@code {"value": <parent value>}} for the same user, e.g. {@code {"value": true}} ("parent is on") or
 * {@code {"value": {"$exists": true}}} ("parent is live"). A parent that is off, archived or absent from the payload
 * evaluates to null, so its dependents fail closed.
 *
 * <p>On a feature, unmet prerequisites turn the whole feature off (null); on a rule, they only skip that rule.
 */
public record Prerequisite(String featureKey, JsonNode condition) {

    public static List<Prerequisite> normalize(List<Prerequisite> prerequisites) {
        return prerequisites == null ? List.of() : List.copyOf(prerequisites);
    }
}
