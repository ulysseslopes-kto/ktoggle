package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ktogroup.ktoggle.evaluation.EvaluationResult;
import com.ktogroup.ktoggle.evaluation.GrowthBookEvaluator;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.ExperimentRule;
import com.ktogroup.ktoggle.feature.ExperimentRule.Variation;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.feature.RuleSchedule;
import com.ktogroup.ktoggle.feature.ValueType;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.callback.TrackingCallback;
import growthbook.sdk.java.model.Experiment;
import growthbook.sdk.java.model.ExperimentResult;
import growthbook.sdk.java.model.GBContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Contract between ktoggle's compiler and the official growthbook-sdk-java used by the KTO Java services:
 * payloads produced by {@link PayloadCompiler} must evaluate, in the real SDK, to what the configuration means.
 */
class GrowthBookSdkContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadCompiler compiler = new PayloadCompiler();
    private final GrowthBookEvaluator evaluator = new GrowthBookEvaluator(objectMapper);
    private final SdkConnection prd = new SdkConnection("sdk-test1234", "test", "prd", List.of(), null, null, null, null);
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void scheduled_rules_are_served_only_inside_their_window() {
        Instant start = NOW.plus(Duration.ofHours(1));
        Instant end = NOW.plus(Duration.ofHours(2));
        Rule promo = new ForceRule("fr_promo", "Weekend promo", true, null, List.of(), BooleanNode.TRUE,
                new RuleSchedule(start, end));
        Feature feature = feature("promo", ValueType.BOOLEAN, BooleanNode.FALSE, new EnvironmentSettings(true, List.of(promo)));
        Function<Instant, ObjectNode> at = instant -> compiler.compile(prd, List.of(feature), Map.of(), instant).payload();

        assertThat(sdkIsOn(at.apply(NOW), "promo", "{}")).as("before the window").isFalse();
        assertThat(at.apply(NOW).path("features").path("promo").has("rules")).isFalse();
        assertThat(sdkIsOn(at.apply(start), "promo", "{}")).as("start is inclusive").isTrue();
        assertThat(sdkIsOn(at.apply(end.minusMillis(1)), "promo", "{}")).isTrue();
        assertThat(sdkIsOn(at.apply(end), "promo", "{}")).as("end is exclusive").isFalse();
        assertThat(at.apply(start).path("features").path("promo").path("rules").get(0).has("schedule"))
                .as("the schedule itself never reaches the SDK").isFalse();
    }

    @Test
    void experiment_splits_users_by_weight_sticky_and_reports_exposures() {
        Rule experiment = experiment("exp-checkout", 1.0, 0.3, 0.7, null);
        ObjectNode payload = compile(feature("checkout-layout", ValueType.STRING, TextNode.valueOf("classic"),
                new EnvironmentSettings(true, List.of(experiment))));

        long treatment = IntStream.range(0, 3000)
                .filter(i -> "new".equals(sdkValue(payload, "checkout-layout", "{\"id\":\"u" + i + "\"}")))
                .count();
        assertThat(treatment).as("~70% treatment").isBetween(1950L, 2250L);

        EvaluationResult first = evaluator.evaluate(payload, "checkout-layout", attrs("{\"id\":\"u42\"}"));
        assertThat(first.source()).isEqualTo("experiment");
        assertThat(first.experiment().trackingKey()).isEqualTo("exp-checkout");
        assertThat(first.experiment().inExperiment()).isTrue();
        assertThat(first.experiment().variationKey()).isIn("control", "treatment");
        assertThat(IntStream.range(0, 20).mapToObj(i -> evaluator.evaluate(payload, "checkout-layout", attrs("{\"id\":\"u42\"}"))
                .experiment().variationKey())).as("sticky").containsOnly(first.experiment().variationKey());

        List<String> tracked = new ArrayList<>();
        new GrowthBook(GBContext.builder().featuresJson(payload.path("features").toString()).attributesJson("{\"id\":\"u7\"}")
                .trackingCallback(new TrackingCallback() {
                    @Override
                    public <T> void onTrack(Experiment<T> exp, ExperimentResult<T> res) {
                        tracked.add(exp.getKey() + ":" + res.getKey());
                    }
                }).build()).getFeatureValue("checkout-layout", "x");
        assertThat(tracked).singleElement().satisfies(t -> assertThat(t).startsWith("exp-checkout:"));
    }

    @Test
    void experiment_respects_coverage_targeting_and_missing_hash_attribute() {
        Rule nobody = experiment("exp-none", 0.0, 0.5, 0.5, null);
        Rule brOnly = experiment("exp-br", 1.0, 0.5, 0.5, cond("{\"country\":\"BR\"}"));
        ObjectNode payload = compile(feature("layout", ValueType.STRING, TextNode.valueOf("classic"),
                new EnvironmentSettings(true, List.of(nobody, brOnly))));

        EvaluationResult excluded = evaluator.evaluate(payload, "layout", attrs("{\"id\":\"u1\",\"country\":\"AR\"}"));
        assertThat(excluded.value().asText()).isEqualTo("classic");
        assertThat(excluded.experiment()).isNull();
        assertThat(evaluator.evaluate(payload, "layout", attrs("{\"id\":\"u1\",\"country\":\"BR\"}")).experiment().trackingKey())
                .isEqualTo("exp-br");
        assertThat(sdkValue(payload, "layout", "{\"country\":\"BR\"}")).as("no hash attribute → not bucketed").isEqualTo("classic");
        assertThat(payload.path("features").path("layout").path("rules").get(1).path("meta").get(1).path("name").asText())
                .isEqualTo("New layout");
    }

    private static Rule experiment(String key, double coverage, double controlWeight, double treatmentWeight, JsonNode condition) {
        return new ExperimentRule("fr_" + key, "Checkout layout test", true, condition, List.of(),
                key, "id", coverage, List.of(
                new Variation("control", "Control", TextNode.valueOf("classic"), controlWeight),
                new Variation("treatment", "New layout", TextNode.valueOf("new"), treatmentWeight)),
                2, null);
    }

    @Test
    void force_rule_applies_only_when_condition_matches() {
        ObjectNode payload = compile(feature("new-checkout", ValueType.BOOLEAN, BooleanNode.FALSE,
                new EnvironmentSettings(true, List.of(force("fr_br", cond("{\"country\":\"BR\"}"), BooleanNode.TRUE)))));

        assertThat(sdkIsOn(payload, "new-checkout", "{\"country\":\"BR\"}")).isTrue();
        assertThat(sdkIsOn(payload, "new-checkout", "{\"country\":\"AR\"}")).isFalse();
        EvaluationResult result = evaluator.evaluate(payload, "new-checkout", attrs("{\"country\":\"BR\"}"));
        assertThat(result.ruleId()).isEqualTo("fr_br");
        assertThat(result.trace()).singleElement().satisfies(t -> assertThat(t.selected()).isTrue());
    }

    @Test
    void disabled_environment_omits_the_feature_so_sdk_evaluates_it_off() {
        ObjectNode payload = compile(feature("dark-mode", ValueType.BOOLEAN, BooleanNode.TRUE, EnvironmentSettings.DISABLED));

        assertThat(payload.path("features").has("dark-mode")).isFalse();
        assertThat(sdkIsOn(payload, "dark-mode", "{}")).isFalse();
        assertThat(evaluator.evaluate(payload, "dark-mode", attrs("{}")).source()).isEqualTo("unknownFeature");
    }

    @Test
    void disabled_rules_are_not_published() {
        Rule disabled = new ForceRule("fr_off", null, false, null, List.of(), BooleanNode.TRUE);
        ObjectNode payload = compile(feature("f", ValueType.BOOLEAN, BooleanNode.FALSE, new EnvironmentSettings(true, List.of(disabled))));

        assertThat(payload.path("features").path("f").has("rules")).isFalse();
        assertThat(sdkIsOn(payload, "f", "{}")).isFalse();
    }

    @Test
    void rollout_coverage_is_deterministic_and_close_to_the_target() {
        Rule rollout = new RolloutRule("fr_half", null, true, null, List.of(), BooleanNode.TRUE, 0.5, "userId");
        ObjectNode payload = compile(feature("half", ValueType.BOOLEAN, BooleanNode.FALSE, new EnvironmentSettings(true, List.of(rollout))));

        long on = IntStream.range(0, 2000).filter(i -> sdkIsOn(payload, "half", "{\"userId\":\"u" + i + "\"}")).count();
        assertThat(on).isBetween(900L, 1100L);
        boolean first = sdkIsOn(payload, "half", "{\"userId\":\"u42\"}");
        assertThat(IntStream.range(0, 20).allMatch(i -> sdkIsOn(payload, "half", "{\"userId\":\"u42\"}") == first)).isTrue();
        assertThat(sdkIsOn(payload, "half", "{}")).as("users without the hash attribute are excluded").isFalse();
    }

    @Test
    void list_and_condition_saved_groups_are_inlined_with_and() {
        SavedGroup vips = new SavedGroup("vips", "VIPs", null, SavedGroupType.LIST, "userId",
                List.of(TextNode.valueOf("u1"), TextNode.valueOf("u2")), null, null, null, null);
        SavedGroup adults = new SavedGroup("adults", "Adults", null, SavedGroupType.CONDITION, null, null,
                cond("{\"age\":{\"$gte\":18}}"), null, null, null);
        Rule rule = new ForceRule("fr_g", null, true, cond("{\"country\":\"BR\"}"), List.of("vips", "adults"), TextNode.valueOf("gold"));
        ObjectNode payload = compiler.compile(prd,
                List.of(feature("tier", ValueType.STRING, TextNode.valueOf("std"), new EnvironmentSettings(true, List.of(rule)))),
                Map.of("vips", vips, "adults", adults), NOW).payload();

        assertThat(payload.path("features").path("tier").path("rules").get(0).path("condition").has("$and")).isTrue();
        assertThat(sdkValue(payload, "tier", "{\"userId\":\"u1\",\"age\":30,\"country\":\"BR\"}")).isEqualTo("gold");
        assertThat(sdkValue(payload, "tier", "{\"userId\":\"u3\",\"age\":30,\"country\":\"BR\"}")).isEqualTo("std");
        assertThat(sdkValue(payload, "tier", "{\"userId\":\"u1\",\"age\":15,\"country\":\"BR\"}")).isEqualTo("std");
        assertThat(sdkValue(payload, "tier", "{\"userId\":\"u1\",\"age\":30,\"country\":\"PT\"}")).isEqualTo("std");
    }

    @Test
    void first_matching_rule_wins_and_typed_values_survive() {
        JsonNode config = objectNode("{\"limit\":10,\"providers\":[\"a\",\"b\"]}");
        Rule first = force("fr_1", cond("{\"country\":\"BR\"}"), objectNode("{\"limit\":50}"));
        Rule second = force("fr_2", null, config);
        ObjectNode payload = compile(feature("cfg", ValueType.JSON, objectNode("{}"), new EnvironmentSettings(true, List.of(first, second))));

        assertThat(evaluator.evaluate(payload, "cfg", attrs("{\"country\":\"BR\"}")).value().path("limit").asInt()).isEqualTo(50);
        EvaluationResult other = evaluator.evaluate(payload, "cfg", attrs("{\"country\":\"PT\"}"));
        assertThat(other.ruleId()).isEqualTo("fr_2");
        assertThat(other.value().path("providers")).hasSize(2);
    }

    @Test
    void project_filter_restricts_published_features() {
        SdkConnection payments = new SdkConnection("sdk-pay12345", "payments", "prd", List.of("payments"), null, null, null, null);
        Feature inProject = feature("pix", ValueType.BOOLEAN, BooleanNode.TRUE, new EnvironmentSettings(true, List.of())).withProjectKey("payments");
        Feature other = feature("kyc", ValueType.BOOLEAN, BooleanNode.TRUE, new EnvironmentSettings(true, List.of())).withProjectKey("kyc");

        JsonNode features = compiler.compile(payments, List.of(inProject, other), Map.of(), NOW).payload().path("features");
        assertThat(features.has("pix")).isTrue();
        assertThat(features.has("kyc")).isFalse();
    }

    private ObjectNode compile(Feature feature) {
        return compiler.compile(prd, List.of(feature), Map.of(), NOW).payload();
    }

    /** Exactly how KTO services use the SDK (see player-service GrowthBookConfiguration). */
    private boolean sdkIsOn(ObjectNode payload, String key, String attributes) {
        return Boolean.TRUE.equals(new GrowthBook(GBContext.builder()
                .featuresJson(payload.path("features").toString()).attributesJson(attributes).build()).isOn(key));
    }

    private String sdkValue(ObjectNode payload, String key, String attributes) {
        return new GrowthBook(GBContext.builder()
                .featuresJson(payload.path("features").toString()).attributesJson(attributes).build())
                .getFeatureValue(key, "<none>");
    }

    private static Feature feature(String key, ValueType type, JsonNode defaultValue, EnvironmentSettings prdSettings) {
        return new Feature(key, null, type, defaultValue, null, null, List.of(), false, Map.of("prd", prdSettings), 1,
                null, "test", null, "test", 0L);
    }

    private static Rule force(String id, JsonNode condition, JsonNode value) {
        return new ForceRule(id, null, true, condition, List.of(), value);
    }

    private JsonNode cond(String json) {
        return objectNode(json);
    }

    private JsonNode attrs(String json) {
        return objectNode(json);
    }

    private JsonNode objectNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
