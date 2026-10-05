package com.ktogroup.ktoggle.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrerequisiteValidatorTest {

    private static final JsonNode IS_ON = JsonNodeFactory.instance.objectNode().put("value", true);

    private final PrerequisiteValidator validator = new PrerequisiteValidator(new ConditionValidator());
    private final Map<String, Feature> features = new HashMap<>(Map.of(
            "a", feature("a", "payments"),
            "b", feature("b", "payments"),
            "other", feature("other", "casino")));

    @Test
    void accepts_parents_of_the_same_project() {
        assertThatCode(() -> validator.validate("c", snapshot("c", "payments", List.of(on("a"), on("b"))), features))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_unknown_self_duplicated_and_cross_project_parents() {
        assertInvalid(snapshot("c", "payments", List.of(on("ghost"))), "unknown or archived feature 'ghost'");
        assertInvalid(snapshot("a", "payments", List.of(on("a"))), "cannot depend on itself");
        assertInvalid(snapshot("c", "payments", List.of(on("a"), on("a"))), "'a' is listed twice");
        assertInvalid(snapshot("c", "payments", List.of(on("other"))), "must be in the same project");
        assertInvalid(snapshot("c", "payments", List.of(new Prerequisite("a", null))), "condition is required");
    }

    @Test
    void conditions_may_only_reference_the_parent_value() {
        JsonNode onCountry = JsonNodeFactory.instance.objectNode().put("country", "BR");
        assertThatThrownBy(() -> validator.validate("c", snapshot("c", "payments", List.of(new Prerequisite("a", onCountry))), features))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejects_cycles_through_feature_and_rule_prerequisites() {
        features.put("a", feature("a", "payments").withPrerequisites(List.of(on("b"))));
        Rule dependsOnA = new ForceRule("fr_x", null, true, null, List.of(), BooleanNode.TRUE, null, List.of(on("a")), null, null);
        FeatureSnapshot b = snapshot("b", "payments", List.of())
                .withEnvironments(Map.of("prd", new EnvironmentSettings(true, List.of(dependsOnA))));

        assertInvalid(b, "Circular prerequisite: b → a → b");
        assertInvalid(snapshot("b", "payments", List.of(on("a"))), "Circular prerequisite: b → a → b");
    }

    @Test
    void finds_long_cycles_and_ignores_diamonds() {
        Map<String, Set<String>> graph = Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a"));
        assertThat(PrerequisiteValidator.findCycle("a", graph)).containsExactly("a", "b", "c", "a");
        Map<String, Set<String>> diamond = Map.of("a", Set.of("b", "c"), "b", Set.of("d"), "c", Set.of("d"));
        assertThat(PrerequisiteValidator.findCycle("a", diamond)).isEmpty();
    }

    private void assertInvalid(FeatureSnapshot snapshot, String message) {
        assertThatThrownBy(() -> validator.validate(snapshot.key(), snapshot, features))
                .isInstanceOf(ValidationException.class).hasMessageContaining(message);
    }

    private static Prerequisite on(String parent) {
        return new Prerequisite(parent, IS_ON);
    }

    private static FeatureSnapshot snapshot(String key, String project, List<Prerequisite> prerequisites) {
        return new FeatureSnapshot(key, project, ValueType.BOOLEAN, BooleanNode.FALSE, null, null, List.of(), false,
                prerequisites, Map.of());
    }

    private static Feature feature(String key, String project) {
        return new Feature(key, project, ValueType.BOOLEAN, BooleanNode.FALSE, null, null, List.of(), false, Map.of(), 1,
                Instant.EPOCH, "t", Instant.EPOCH, "t", 0L);
    }
}
