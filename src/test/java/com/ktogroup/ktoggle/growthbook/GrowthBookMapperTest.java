package com.ktogroup.ktoggle.growthbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.bundle.PayloadCompiler;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.ExperimentRule;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.ValueType;
import com.ktogroup.ktoggle.growthbook.GrowthBookMapper.FeaturePlan;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Against responses captured from a real (local, open source) GrowthBook: see src/test/resources/growthbook. */
class GrowthBookMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GrowthBookMapper mapper = new GrowthBookMapper(objectMapper);

    @Test
    void imported_features_behave_exactly_like_growthbook_serves_them() throws IOException {
        Map<String, SavedGroup> groups = new HashMap<>();
        for (JsonNode gb : fixture("saved-groups").path("savedGroups")) {
            SavedGroupCommand c = mapper.savedGroup(gb);
            groups.put(c.key(), new SavedGroup(c.key(), c.name(), c.description(), c.type(), c.attributeKey(), c.values(), c.condition(),
                    NOW, NOW, 0L));
        }
        List<Feature> features = new ArrayList<>();
        for (JsonNode gb : fixture("features").path("features")) {
            FeaturePlan plan = mapper.feature(gb, Map.of("production", "production"), id -> Optional.empty());
            assertThat(plan.warnings()).as(plan.key()).isEmpty();
            features.add(new Feature(plan.key(), null, plan.valueType(), plan.defaultValue(), plan.description(), plan.owner(),
                    plan.tags(), plan.archived(), plan.prerequisites(), plan.environments(), 1, NOW, "t", NOW, "t", 0L));
        }
        SdkConnection connection = new SdkConnection("sdk-46eVWBICRMOJ8mHs", "GB demo", "production", List.of(), null, NOW, NOW, 0L);
        ObjectNode ktoggle = new PayloadCompiler().compile(connection, features, groups, NOW).payload();
        JsonNode growthbook = fixture("sdk-payload");

        List<Attribute> catalog = List.of(attribute("id", AttributeDatatype.STRING, true), attribute("country", AttributeDatatype.STRING, false),
                attribute("vipLevel", AttributeDatatype.NUMBER, false));
        List<ObjectNode> samples = AttributeSampler.samples(catalog, List.of(growthbook, ktoggle), 3000, 7L);
        ShadowComparator.Comparison comparison = new ShadowComparator(objectMapper).compare(growthbook, ktoggle.path("features"), samples);

        assertThat(comparison.featuresCompared()).isEqualTo(5);
        assertThat(comparison.divergences()).as("same value for every simulated user").isEmpty();
    }

    @Test
    void rollouts_keep_growthbook_seeds_and_experiments_its_hash_version() throws IOException {
        Map<String, FeaturePlan> plans = new HashMap<>();
        for (JsonNode gb : fixture("features").path("features")) {
            plans.put(gb.path("id").asText(), mapper.feature(gb, Map.of("production", "prd"), id -> Optional.empty()));
        }
        EnvironmentSettings checkout = plans.get("gb-new-checkout").environments().get("prd");
        ForceRule testers = (ForceRule) checkout.rules().get(0);
        assertThat(testers.savedGroupsAny()).hasSize(1);
        RolloutRule rollout = (RolloutRule) checkout.rules().get(1);
        assertThat(rollout.seed()).isEqualTo(rollout.id()).startsWith("fr_");
        assertThat(rollout.condition().path("country").asText()).isEqualTo("BR");

        ExperimentRule experiment = (ExperimentRule) plans.get("gb-button-copy").environments().get("prd").rules().getFirst();
        assertThat(experiment.hashVersion()).as("GrowthBook inline experiments hash with v1").isEqualTo(1);
        assertThat(experiment.variations()).extracting(ExperimentRule.Variation::key).containsExactly("0", "1");
        assertThat(experiment.variations().get(1).value().asText()).isEqualTo("Add funds");

        assertThat(((ForceRule) plans.get("gb-max-bet").environments().get("prd").rules().get(1)).savedGroupsNone()).hasSize(1);
        assertThat(plans.get("gb-welcome-bonus").defaultValue().path("enabled").asBoolean()).isFalse();
        assertThat(plans.get("gb-welcome-bonus").valueType()).isEqualTo(ValueType.JSON);
        assertThat(plans.get("gb-dark-mode").environments().get("prd").enabled()).isFalse();
    }

    @Test
    void unsupported_rules_and_unmapped_environments_are_reported() throws IOException {
        ObjectNode feature = (ObjectNode) fixture("features").path("features").get(0).deepCopy();
        ObjectNode rule = ((ObjectNode) feature.path("environments").path("production").path("rules").get(0));
        rule.put("type", "safe-rollout");
        ((ObjectNode) feature.path("environments")).set("staging", objectMapper.createObjectNode().put("enabled", true));

        FeaturePlan plan = mapper.feature(feature, Map.of("production", "production"), id -> Optional.empty());

        assertThat(plan.warnings()).anyMatch(w -> w.contains("safe-rollout")).anyMatch(w -> w.contains("'staging' is not mapped"));
        assertThatThrownBy(() -> mapper.feature(objectMapper.createObjectNode().put("id", "x").put("valueType", "date"),
                Map.of(), id -> Optional.empty())).hasMessageContaining("unsupported value type");
    }

    @Test
    void experiment_refs_keep_the_phase_targeting_and_report_namespaces() throws IOException {
        JsonNode experiment = objectMapper.readTree("""
                {"id": "exp_1", "status": "running", "trackingKey": "copy", "variations": [
                   {"variationId": "v0", "key": "0"}, {"variationId": "v1", "key": "1"}],
                 "phases": [{"coverage": 1, "trafficSplit": [{"variationId": "v0", "weight": 0.5}, {"variationId": "v1", "weight": 0.5}],
                   "savedGroupTargeting": [{"matchType": "all", "savedGroups": ["vips"]}, {"matchType": "none", "savedGroups": ["banned"]}],
                   "prerequisites": [{"id": "parent", "condition": "{\\"value\\": true}"}]}]}""");
        JsonNode rule = objectMapper.readTree("""
                {"type": "experiment-ref", "id": "r1", "experimentId": "exp_1",
                 "variations": [{"variationId": "v0", "value": "a"}, {"variationId": "v1", "value": "b"}]}""");

        ExperimentRule mapped = (ExperimentRule) mapper.rule(rule, ValueType.STRING, id -> Optional.of(experiment));

        assertThat(mapped.savedGroups()).as("restricted as in GrowthBook, not open to everyone").containsExactly("vips");
        assertThat(mapped.savedGroupsNone()).containsExactly("banned");
        assertThat(mapped.prerequisites()).singleElement().satisfies(p -> {
            assertThat(p.featureKey()).isEqualTo("parent");
            assertThat(p.condition().path("value").asBoolean()).isTrue();
        });

        ObjectNode namespaced = experiment.deepCopy();
        ((ObjectNode) namespaced.path("phases").get(0)).set("namespace", objectMapper.readTree("""
                {"namespaceId": "ns", "range": [0, 0.5]}"""));
        assertThatThrownBy(() -> mapper.rule(rule, ValueType.STRING, id -> Optional.of(namespaced)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespaces");
    }

    @Test
    void an_invalid_schedule_timestamp_skips_the_rule_not_the_feature() throws IOException {
        ObjectNode feature = (ObjectNode) fixture("features").path("features").get(0).deepCopy();
        ObjectNode rule = ((ObjectNode) feature.path("environments").path("production").path("rules").get(0));
        rule.set("scheduleRules", objectMapper.readTree("[{\"enabled\": true, \"timestamp\": \"tomorrow\"}]"));

        FeaturePlan plan = mapper.feature(feature, Map.of("production", "production"), id -> Optional.empty());

        assertThat(plan.warnings()).anyMatch(w -> w.contains("invalid schedule timestamp"));
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/growthbook/" + name + ".json")) {
            return objectMapper.readTree(in);
        }
    }

    private static Attribute attribute(String key, AttributeDatatype type, boolean hash) {
        return new Attribute(key, type, null, hash, false, List.of(), false, NOW, NOW, 0L);
    }
}
