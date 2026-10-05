package com.ktogroup.ktoggle.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.BundleBody;
import com.ktogroup.ktoggle.bundle.Evaluators;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import org.junit.jupiter.api.Test;

class GrowthBookEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GrowthBookEvaluator evaluator = new GrowthBookEvaluator(mapper);

    @Test
    void the_evaluator_bundles_are_published_for_is_supported() {
        assertThatCode(() -> evaluator.requireSupported(Evaluators.current())).doesNotThrowAnyException();
    }

    @Test
    void bundles_for_another_spec_hash_version_or_engine_are_refused() {
        BundleBody.Evaluator current = Evaluators.current();

        assertUnsupported(new BundleBody.Evaluator("other-spec", current.hashVersion(), current.referenceEvaluator()));
        assertUnsupported(new BundleBody.Evaluator(current.spec(), 2, current.referenceEvaluator()));
        assertUnsupported(new BundleBody.Evaluator(current.spec(), current.hashVersion(), null));
        assertUnsupported(new BundleBody.Evaluator(current.spec(), current.hashVersion(), "growthbook-sdk-js@1.0.0"));
    }

    @Test
    void a_matching_force_rule_wins_and_the_trace_explains_it() throws Exception {
        EvaluationResult result = evaluator.evaluate(payload("""
                {"features":{"checkout":{"defaultValue":false,"rules":[
                  {"id":"fr_no","condition":{"country":"AR"},"force":false},
                  {"id":"fr_yes","condition":{"country":"BR"},"force":true}]}}}"""), "checkout", mapper.readTree("{\"country\":\"BR\"}"));

        assertThat(result.value().asBoolean()).isTrue();
        assertThat(result.source()).isEqualTo("force");
        assertThat(result.ruleId()).isEqualTo("fr_yes");
        assertThat(result.evaluator()).isEqualTo(Evaluators.REFERENCE_EVALUATOR);
        assertThat(result.trace()).extracting(EvaluationResult.RuleTrace::conditionMatched).containsExactly(false, true);
        assertThat(result.trace()).extracting(EvaluationResult.RuleTrace::selected).containsExactly(false, true);
    }

    @Test
    void without_a_matching_rule_the_default_value_is_served() throws Exception {
        EvaluationResult result = evaluator.evaluate(payload("""
                {"features":{"checkout":{"defaultValue":"a","rules":[{"id":"fr_1","condition":{"country":"AR"},"force":"b"}]}}}"""),
                "checkout", mapper.readTree("{\"country\":\"BR\"}"));

        assertThat(result.value().asText()).isEqualTo("a");
        assertThat(result.source()).isEqualTo("defaultValue");
        assertThat(result.ruleId()).isNull();
        assertThat(result.trace().getFirst().selected()).isFalse();
    }

    @Test
    void rules_without_a_condition_always_match_and_rollout_rules_are_labelled() throws Exception {
        EvaluationResult result = evaluator.evaluate(payload("""
                {"features":{"checkout":{"defaultValue":false,"rules":[
                  {"id":"fr_all","force":true,"coverage":1.0,"hashAttribute":"userId"}]}}}"""), "checkout",
                mapper.readTree("{\"userId\":\"u-1\"}"));

        assertThat(result.value().asBoolean()).isTrue();
        assertThat(result.trace().getFirst().type()).isEqualTo("rollout");
        assertThat(result.trace().getFirst().conditionMatched()).isTrue();
    }

    @Test
    void json_values_come_back_as_json() throws Exception {
        EvaluationResult result = evaluator.evaluate(payload("""
                {"features":{"theme":{"defaultValue":{"color":"red","sizes":[1,2]}}}}"""), "theme", NullNode.getInstance());

        assertThat(result.value().path("color").asText()).isEqualTo("red");
        assertThat(result.value().path("sizes")).hasSize(2);
        assertThat(result.trace()).isEmpty();
    }

    @Test
    void unknown_features_evaluate_to_null_and_missing_attributes_to_an_empty_set() throws Exception {
        EvaluationResult result = evaluator.evaluate(payload("{\"features\":{}}"), "nope", null);

        assertThat(result.value().isNull()).isTrue();
        assertThat(result.source()).isEqualTo("unknownFeature");
    }

    private void assertUnsupported(BundleBody.Evaluator evaluatorSpec) {
        assertThatThrownBy(() -> evaluator.requireSupported(evaluatorSpec))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.UNSUPPORTED_EVALUATOR));
    }

    private ObjectNode payload(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }
}
