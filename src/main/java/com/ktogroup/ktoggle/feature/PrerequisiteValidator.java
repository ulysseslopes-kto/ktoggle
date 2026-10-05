package com.ktogroup.ktoggle.feature;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Prerequisites must point to existing, non-archived features other than the feature itself and in the same project
 * (so every SDK connection serving the feature also serves its parents), carry a condition on {@code value} only, and
 * never form a cycle — across feature-level and rule-level dependencies, through any chain.
 * (SDKs would detect a cycle at evaluation time and serve null; it is rejected here so it never reaches them.)
 */
@Component
@RequiredArgsConstructor
public class PrerequisiteValidator {

    static final int MAX_PREREQUISITES = 10;
    private static final Set<String> VALUE_ONLY = Set.of("value");

    private final ConditionValidator conditionValidator;

    /**
     * @param featureKey the feature whose proposed content is {@code snapshot}
     * @param features   every active feature by key (the live version of {@code featureKey} is ignored)
     */
    public void validate(String featureKey, FeatureSnapshot snapshot, Map<String, Feature> features) {
        validateList("prerequisites", snapshot, snapshot.prerequisites(), features);
        snapshot.environments().forEach((environment, settings) -> {
            List<Rule> rules = settings.rules();
            for (int i = 0; i < rules.size(); i++) {
                validateList("environments.%s.rules[%d].prerequisites".formatted(environment, i), snapshot,
                        rules.get(i).prerequisites(), features);
            }
        });
        Map<String, Set<String>> graph = new HashMap<>();
        features.values().forEach(feature -> graph.put(feature.key(), parents(feature.snapshot())));
        graph.put(featureKey, parents(snapshot));
        List<String> cycle = findCycle(featureKey, graph);
        if (!cycle.isEmpty()) {
            throw ValidationException.of("Circular prerequisite: " + String.join(" → ", cycle));
        }
    }

    /** Every feature the snapshot depends on, at feature level or in any rule of any environment. */
    public static Set<String> parents(FeatureSnapshot snapshot) {
        Set<String> parents = new LinkedHashSet<>();
        snapshot.prerequisites().forEach(p -> parents.add(p.featureKey()));
        snapshot.environments().values().forEach(settings ->
                settings.rules().forEach(rule -> rule.prerequisites().forEach(p -> parents.add(p.featureKey()))));
        return parents;
    }

    private void validateList(String at, FeatureSnapshot child, List<Prerequisite> prerequisites, Map<String, Feature> features) {
        if (prerequisites.size() > MAX_PREREQUISITES) {
            throw ValidationException.of("%s: at most %d prerequisites".formatted(at, MAX_PREREQUISITES));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < prerequisites.size(); i++) {
            Prerequisite prerequisite = prerequisites.get(i);
            String where = "%s[%d]".formatted(at, i);
            if (prerequisite == null || prerequisite.featureKey() == null || prerequisite.featureKey().isBlank()) {
                throw ValidationException.of(where + ": featureKey is required");
            }
            String parent = prerequisite.featureKey();
            if (parent.equals(child.key())) {
                throw ValidationException.of(where + ": a feature cannot depend on itself");
            }
            if (!features.containsKey(parent)) {
                throw ValidationException.of("%s: unknown or archived feature '%s'".formatted(where, parent));
            }
            if (!Objects.equals(features.get(parent).projectKey(), child.projectKey())) {
                // a connection that serves the child must also serve the parent, or the child would always be off
                throw ValidationException.of("%s: '%s' must be in the same project as this feature".formatted(where, parent));
            }
            if (!seen.add(parent)) {
                throw ValidationException.of("%s: '%s' is listed twice".formatted(where, parent));
            }
            if (prerequisite.condition() == null || !prerequisite.condition().isObject() || prerequisite.condition().isEmpty()) {
                throw ValidationException.of(where + ": condition is required, e.g. {\"value\": true}");
            }
            conditionValidator.validate(prerequisite.condition(), VALUE_ONLY);
        }
    }

    /** Depth-first search for a path from {@code start} back to itself; returns it (start ... start) or empty. */
    static List<String> findCycle(String start, Map<String, Set<String>> graph) {
        List<String> path = new ArrayList<>();
        path.add(start);
        return dfs(start, start, graph, path, new HashSet<>()) ? path : List.of();
    }

    private static boolean dfs(String start, String node, Map<String, Set<String>> graph, List<String> path, Set<String> visited) {
        for (String parent : graph.getOrDefault(node, Set.of())) {
            path.add(parent);
            if (parent.equals(start)) {
                return true;
            }
            if (visited.add(parent) && dfs(start, parent, graph, path, visited)) {
                return true;
            }
            path.removeLast();
        }
        return false;
    }
}
