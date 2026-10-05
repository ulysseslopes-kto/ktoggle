package com.ktogroup.ktoggle.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.ktogroup.ktoggle.bundle.BundleBody;
import com.ktogroup.ktoggle.bundle.Evaluators;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.ExperimentResult;
import growthbook.sdk.java.model.FeatureResult;
import growthbook.sdk.java.model.GBContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Evaluates a GrowthBook-format payload with the official growthbook-sdk-java — the same code the KTO Java
 * services run — instead of a re-implementation, so simulation and replay cannot drift from production behaviour.
 */
@Component
@RequiredArgsConstructor
public class GrowthBookEvaluator {

    private static final Gson GSON = new Gson();

    private final ObjectMapper objectMapper;

    /** Rejects bundles produced for semantics this build cannot reproduce faithfully. */
    public void requireSupported(BundleBody.Evaluator evaluator) {
        boolean supported = Evaluators.GROWTHBOOK_SPEC.equals(evaluator.spec())
                && evaluator.hashVersion() == Evaluators.HASH_VERSION
                && evaluator.referenceEvaluator() != null
                && evaluator.referenceEvaluator().startsWith("growthbook-sdk-java@");
        if (!supported) {
            throw new ValidationException(MessageCode.UNSUPPORTED_EVALUATOR,
                    "Bundle evaluator %s is not supported by this ktoggle version".formatted(evaluator));
        }
    }

    public EvaluationResult evaluate(ObjectNode payload, String featureKey, JsonNode attributes) {
        JsonNode features = payload.path("features");
        String attributesJson = write(attributes == null || attributes.isNull() ? objectMapper.createObjectNode() : attributes);
        GrowthBook growthBook = new GrowthBook(GBContext.builder()
                .featuresJson(write(features))
                .attributesJson(attributesJson)
                .build());
        FeatureResult<Object> result = growthBook.evalFeature(featureKey, Object.class);
        String ruleId = result.getRuleId() == null || result.getRuleId().isBlank() ? null : result.getRuleId();

        List<EvaluationResult.RuleTrace> trace = new ArrayList<>();
        for (JsonNode rule : features.path(featureKey).path("rules")) {
            String id = rule.path("id").asText(null);
            boolean matched = (!rule.hasNonNull("condition")
                    || Boolean.TRUE.equals(growthBook.evaluateCondition(attributesJson, write(rule.get("condition")))))
                    && parentsPass(growthBook, rule.path("parentConditions"));
            trace.add(new EvaluationResult.RuleTrace(id, type(rule), matched, id != null && id.equals(ruleId)));
        }
        String source = result.getSource() == null ? null : result.getSource().toString();
        return new EvaluationResult(featureKey, toJson(result.getValue()), source, ruleId, Evaluators.REFERENCE_EVALUATOR,
                List.copyOf(trace), assignment(result));
    }

    private static String type(JsonNode rule) {
        if (isGate(rule)) {
            return "prerequisite";
        }
        return rule.has("variations") ? "experiment" : rule.has("coverage") ? "rollout" : "force";
    }

    /** The leading rule compiled from feature-level prerequisites (it never serves a value itself). */
    private static boolean isGate(JsonNode rule) {
        return !rule.has("force") && !rule.has("variations") && rule.path("parentConditions").path(0).path("gate").asBoolean(false);
    }

    /** Same check the SDK runs: each parent's value for this user against {@code {"value": ...}}. */
    private boolean parentsPass(GrowthBook growthBook, JsonNode parents) {
        for (JsonNode parent : parents) {
            ObjectNode subject = objectMapper.createObjectNode();
            subject.set("value", toJson(growthBook.evalFeature(parent.path("id").asText(), Object.class).getValue()));
            if (!Boolean.TRUE.equals(growthBook.evaluateCondition(write(subject), write(parent.path("condition"))))) {
                return false;
            }
        }
        return true;
    }

    private static EvaluationResult.ExperimentAssignment assignment(FeatureResult<Object> result) {
        ExperimentResult<Object> experiment = result.getExperimentResult();
        if (experiment == null || result.getExperiment() == null) {
            return null;
        }
        Integer index = experiment.getVariationId();
        return new EvaluationResult.ExperimentAssignment(result.getExperiment().getKey(), experiment.getKey(),
                index == null ? -1 : index, Boolean.TRUE.equals(experiment.getInExperiment()),
                experiment.getBucket() == null ? null : experiment.getBucket().doubleValue());
    }

    /** SDK values are Gson-typed; round-trip through JSON to get a Jackson tree. */
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
