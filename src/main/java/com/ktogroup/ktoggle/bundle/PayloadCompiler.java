package com.ktogroup.ktoggle.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.ExperimentRule;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.Prerequisite;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer between ktoggle's domain and the GrowthBook SDK payload format
 * ({@code {"features": {key: {defaultValue, rules: [...]}}}}).
 *
 * <p>Compatibility decisions (validated by the contract tests against the official growthbook-sdk-java):
 * <ul>
 *   <li>Archived features and features disabled in the environment are omitted — SDKs then evaluate them to
 *   {@code null}/off, exactly like GrowthBook does for disabled environments.</li>
 *   <li>Disabled rules, and scheduled rules outside their window, are omitted (SDKs do not evaluate schedules).</li>
 *   <li>Prerequisites become {@code parentConditions}: feature-level ones as a leading gate rule, rule-level ones on
 *   the rule itself.</li>
 *   <li>Saved groups are inlined into the rule condition ({@code $and}), because growthbook-sdk-java 0.10.x
 *   does not support {@code $inGroup}.</li>
 * </ul>
 */
@Component
public class PayloadCompiler {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * @param at instant the payload is compiled for: scheduled rules are included only inside their window
     */
    public CompiledPayload compile(SdkConnection connection, List<Feature> features, Map<String, SavedGroup> savedGroups,
                                   Instant at) {
        ObjectNode compiledFeatures = JSON.objectNode();
        Map<String, Integer> sources = new TreeMap<>();
        features.stream()
                .filter(feature -> !feature.archived())
                .filter(feature -> connection.includesProject(feature.projectKey()))
                .sorted(Comparator.comparing(Feature::key))
                .forEach(feature -> {
                    EnvironmentSettings settings = feature.environment(connection.environmentKey());
                    if (settings.enabled()) {
                        compiledFeatures.set(feature.key(), compileFeature(feature, settings, savedGroups, at));
                        sources.put(feature.key(), feature.revision());
                    }
                });
        ObjectNode payload = JSON.objectNode();
        payload.set("features", compiledFeatures);
        return new CompiledPayload(payload, sources);
    }

    private static ObjectNode compileFeature(Feature feature, EnvironmentSettings settings, Map<String, SavedGroup> groups,
                                             Instant at) {
        ObjectNode definition = JSON.objectNode();
        definition.set("defaultValue", feature.defaultValue());
        ArrayNode rules = JSON.arrayNode();
        if (!feature.prerequisites().isEmpty()) {
            // GrowthBook gate: when a parent condition fails the SDK stops and serves null (source "prerequisite")
            rules.addObject().set("parentConditions", parentConditions(feature.prerequisites(), true));
        }
        settings.rules().stream().filter(rule -> rule.liveAt(at)).forEach(rule -> rules.add(compileRule(rule, groups)));
        if (!rules.isEmpty()) {
            definition.set("rules", rules);
        }
        return definition;
    }

    private static ObjectNode compileRule(Rule rule, Map<String, SavedGroup> groups) {
        ObjectNode compiled = JSON.objectNode();
        compiled.put("id", rule.id());
        JsonNode condition = condition(rule, groups);
        if (condition != null) {
            compiled.set("condition", condition);
        }
        if (!rule.prerequisites().isEmpty()) {
            compiled.set("parentConditions", parentConditions(rule.prerequisites(), false));
        }
        switch (rule) {
            case ForceRule force -> compiled.set("force", force.value());
            case RolloutRule rollout -> {
                compiled.set("force", rollout.value());
                compiled.put("coverage", rollout.coverage());
                compiled.put("hashAttribute", rollout.hashAttribute());
            }
            case ExperimentRule experiment -> compileExperiment(compiled, experiment);
        }
        return compiled;
    }

    /** GrowthBook inline experiment rule: the SDK buckets the user and reports the exposure to its tracking callback. */
    private static void compileExperiment(ObjectNode compiled, ExperimentRule experiment) {
        compiled.put("key", experiment.trackingKey());
        compiled.put("name", experiment.description() == null || experiment.description().isBlank()
                ? experiment.trackingKey() : experiment.description());
        compiled.put("coverage", experiment.coverage());
        compiled.put("hashAttribute", experiment.hashAttribute());
        compiled.put("hashVersion", experiment.hashVersion());
        if (experiment.seed() != null && !experiment.seed().isBlank()) {
            compiled.put("seed", experiment.seed());
        }
        compiled.put("phase", "0");
        ArrayNode variations = compiled.putArray("variations");
        ArrayNode weights = compiled.putArray("weights");
        ArrayNode meta = compiled.putArray("meta");
        for (ExperimentRule.Variation variation : experiment.variations()) {
            variations.add(variation.value());
            weights.add(variation.weight());
            ObjectNode m = meta.addObject();
            m.put("key", variation.key());
            if (variation.name() != null && !variation.name().isBlank()) {
                m.put("name", variation.name());
            }
        }
    }

    /** {@code gate}: the whole feature is off when unmet; otherwise only the rule is skipped. */
    private static ArrayNode parentConditions(List<Prerequisite> prerequisites, boolean gate) {
        ArrayNode parents = JSON.arrayNode();
        for (Prerequisite prerequisite : prerequisites) {
            ObjectNode parent = parents.addObject();
            parent.put("id", prerequisite.featureKey());
            parent.set("condition", prerequisite.condition());
            if (gate) {
                parent.put("gate", true);
            }
        }
        return parents;
    }

    private static JsonNode condition(Rule rule, Map<String, SavedGroup> groups) {
        List<JsonNode> parts = new ArrayList<>();
        if (rule.condition() != null && rule.condition().isObject() && !rule.condition().isEmpty()) {
            parts.add(rule.condition());
        }
        for (String groupKey : rule.savedGroups()) {
            SavedGroup group = groups.get(groupKey);
            if (group == null) {
                throw new IllegalStateException("Rule %s references missing saved group '%s'".formatted(rule.id(), groupKey));
            }
            parts.add(group.toCondition());
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        ObjectNode and = JSON.objectNode();
        and.putArray("$and").addAll(parts);
        return and;
    }

    /**
     * @param payload the SDK payload body ({@code {"features": ...}})
     * @param sources featureKey &rarr; revision each compiled feature came from
     */
    public record CompiledPayload(ObjectNode payload, Map<String, Integer> sources) {
    }
}
