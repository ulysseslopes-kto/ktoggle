package com.ktogroup.ktoggle.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
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
 *   <li>Disabled rules are omitted.</li>
 *   <li>Saved groups are inlined into the rule condition ({@code $and}), because growthbook-sdk-java 0.10.x
 *   does not support {@code $inGroup}.</li>
 * </ul>
 */
@Component
public class PayloadCompiler {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    public CompiledPayload compile(SdkConnection connection, List<Feature> features, Map<String, SavedGroup> savedGroups) {
        ObjectNode compiledFeatures = JSON.objectNode();
        Map<String, Integer> sources = new TreeMap<>();
        features.stream()
                .filter(feature -> !feature.archived())
                .filter(feature -> connection.includesProject(feature.projectKey()))
                .sorted(Comparator.comparing(Feature::key))
                .forEach(feature -> {
                    EnvironmentSettings settings = feature.environment(connection.environmentKey());
                    if (settings.enabled()) {
                        compiledFeatures.set(feature.key(), compileFeature(feature, settings, savedGroups));
                        sources.put(feature.key(), feature.revision());
                    }
                });
        ObjectNode payload = JSON.objectNode();
        payload.set("features", compiledFeatures);
        return new CompiledPayload(payload, sources);
    }

    private static ObjectNode compileFeature(Feature feature, EnvironmentSettings settings, Map<String, SavedGroup> groups) {
        ObjectNode definition = JSON.objectNode();
        definition.set("defaultValue", feature.defaultValue());
        ArrayNode rules = JSON.arrayNode();
        settings.rules().stream().filter(Rule::enabled).forEach(rule -> rules.add(compileRule(rule, groups)));
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
        compiled.set("force", rule.value());
        switch (rule) {
            case ForceRule ignored -> {
                // force only
            }
            case RolloutRule rollout -> {
                compiled.put("coverage", rollout.coverage());
                compiled.put("hashAttribute", rollout.hashAttribute());
            }
        }
        return compiled;
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
