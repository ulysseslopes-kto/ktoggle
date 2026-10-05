package com.ktogroup.ktoggle.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * @param source as reported by the SDK: {@code defaultValue}, {@code force}, {@code unknownFeature}, ...
 * @param ruleId the rule that produced the value, if any
 * @param trace  per-rule explanation, in evaluation order
 */
public record EvaluationResult(String featureKey, JsonNode value, String source, String ruleId, String evaluator,
                               List<RuleTrace> trace) {

    /**
     * @param conditionMatched whether the rule's targeting condition (saved groups inlined) matched the attributes
     * @param selected         whether this rule produced the value (a matching rollout rule may still exclude
     *                         the user through its coverage)
     */
    public record RuleTrace(String ruleId, String type, boolean conditionMatched, boolean selected) {
    }
}
