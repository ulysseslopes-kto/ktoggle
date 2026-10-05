package com.ktogroup.ktoggle.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * @param source     as reported by the SDK: {@code defaultValue}, {@code force}, {@code experiment},
 *                   {@code unknownFeature}, ...
 * @param ruleId     the rule that produced the value, if any
 * @param trace      per-rule explanation, in evaluation order
 * @param experiment assignment details when the value came from an experiment rule, otherwise null
 */
public record EvaluationResult(String featureKey, JsonNode value, String source, String ruleId, String evaluator,
                               List<RuleTrace> trace, ExperimentAssignment experiment) {

    public EvaluationResult(String featureKey, JsonNode value, String source, String ruleId, String evaluator,
                            List<RuleTrace> trace) {
        this(featureKey, value, source, ruleId, evaluator, trace, null);
    }

    /**
     * @param conditionMatched whether the rule's targeting condition (saved groups inlined) matched the attributes
     * @param selected         whether this rule produced the value (a matching rollout or experiment rule may still
     *                         exclude the user through its coverage)
     */
    public record RuleTrace(String ruleId, String type, boolean conditionMatched, boolean selected) {
    }

    /**
     * What the SDK's tracking callback would report for this user.
     *
     * @param variationKey   key of the assigned variation (meta key), as sent to analytics
     * @param variationIndex position of the variation
     * @param bucket         the user's hash bucket (0..1) — deterministic for the same hash attribute value
     */
    public record ExperimentAssignment(String trackingKey, String variationKey, int variationIndex, boolean inExperiment,
                                       Double bucket) {
    }
}
