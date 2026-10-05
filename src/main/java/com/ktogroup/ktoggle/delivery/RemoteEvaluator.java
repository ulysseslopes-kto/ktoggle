package com.ktogroup.ktoggle.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.ExperimentResult;
import growthbook.sdk.java.model.FeatureResult;
import growthbook.sdk.java.model.GBContext;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GrowthBook remote evaluation: evaluates every feature of a payload for one user with the official SDK and returns a
 * payload with no rules, only values ({@code defaultValue}). When the user is in an experiment, the feature carries a
 * {@code force} rule with {@code tracks}, which makes the client SDK fire its tracking callback exactly as with local
 * evaluation.
 */
@Component
@RequiredArgsConstructor
public class RemoteEvaluator {

    private static final Gson GSON = new Gson();
    /** What the tracking callback needs; weights, coverage and seed (the rollout plan) stay on the server. */
    private static final List<String> EXPERIMENT_FIELDS = List.of("key", "name", "variations", "meta", "hashAttribute", "phase");

    private final ObjectMapper objectMapper;

    /**
     * @param features         {@code features} of the active, verified bundle
     * @param forcedVariations experiment key &rarr; variation index (QA / dev tools)
     * @param forcedFeatures   feature key &rarr; value to return as is (QA / dev tools)
     */
    public ObjectNode evaluate(JsonNode features, JsonNode attributes, Map<String, Integer> forcedVariations,
                               Map<String, JsonNode> forcedFeatures, String url) {
        GrowthBook growthBook = new GrowthBook(GBContext.builder()
                .featuresJson(write(features))
                .attributesJson(write(attributes == null || attributes.isNull() ? objectMapper.createObjectNode() : attributes))
                .forcedVariationsMap(forcedVariations == null ? Map.of() : forcedVariations)
                .url(url)
                .build());
        ObjectNode evaluated = objectMapper.createObjectNode();
        Iterator<String> keys = features.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            ObjectNode definition = evaluated.putObject(key);
            if (forcedFeatures != null && forcedFeatures.containsKey(key)) {
                definition.set("defaultValue", forcedFeatures.get(key));
                continue;
            }
            FeatureResult<Object> result = growthBook.evalFeature(key, Object.class);
            JsonNode value = toJson(result.getValue());
            definition.set("defaultValue", value);
            ExperimentResult<Object> experiment = result.getExperimentResult();
            if (experiment != null && Boolean.TRUE.equals(experiment.getInExperiment()) && result.getExperiment() != null) {
                ObjectNode rule = definition.putArray("rules").addObject();
                rule.set("force", value);
                ObjectNode track = rule.putArray("tracks").addObject();
                track.set("experiment", experimentDefinition(features.path(key), result.getExperiment().getKey()));
                track.set("result", experimentResult(key, experiment));
            }
        }
        return evaluated;
    }

    /** The experiment as compiled in the bundle (what the client SDK would have seen locally), without targeting. */
    private ObjectNode experimentDefinition(JsonNode feature, String experimentKey) {
        ObjectNode definition = objectMapper.createObjectNode();
        for (JsonNode rule : feature.path("rules")) {
            if (experimentKey.equals(rule.path("key").asText(null))) {
                EXPERIMENT_FIELDS.forEach(field -> {
                    if (rule.has(field)) {
                        definition.set(field, rule.get(field));
                    }
                });
                return definition;
            }
        }
        definition.put("key", experimentKey);
        return definition;
    }

    private ObjectNode experimentResult(String featureKey, ExperimentResult<Object> experiment) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("featureId", featureKey);
        result.put("key", experiment.getKey());
        result.put("variationId", experiment.getVariationId());
        result.set("value", toJson(experiment.getValue()));
        result.put("inExperiment", Boolean.TRUE.equals(experiment.getInExperiment()));
        result.put("hashUsed", Boolean.TRUE.equals(experiment.getHashUsed()));
        result.put("hashAttribute", experiment.getHashAttribute());
        result.put("hashValue", experiment.getHashValue() == null ? null : String.valueOf(experiment.getHashValue()));
        if (experiment.getBucket() != null) {
            result.put("bucket", experiment.getBucket().doubleValue());
        }
        result.put("stickyBucketUsed", false);
        return result;
    }

    private JsonNode toJson(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(GSON.toJson(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected SDK value", e);
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
