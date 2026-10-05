package com.ktogroup.ktoggle.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final RuleValidator validator = new RuleValidator(new ConditionValidator());
    private final Map<String, Attribute> attributes = new HashMap<>(Map.of(
            "userId", attribute("userId", AttributeDatatype.STRING),
            "accountNumber", attribute("accountNumber", AttributeDatatype.NUMBER),
            "vip", attribute("vip", AttributeDatatype.BOOLEAN),
            "country", attribute("country", AttributeDatatype.STRING)));
    private final Set<String> groups = Set.of("vips");

    @Test
    void no_rules_means_an_empty_list() {
        assertThat(validator.validate(ValueType.BOOLEAN, null, attributes, groups)).isEmpty();
        assertThat(validator.validate(ValueType.BOOLEAN, List.of(), attributes, groups)).isEmpty();
    }

    @Test
    void rules_without_an_id_get_a_growthbook_style_id_and_existing_ids_are_kept() {
        List<Rule> result = validator.validate(ValueType.BOOLEAN,
                List.of(force(null, true), force(" ", true), force("fr_keep", true)), attributes, groups);

        assertThat(result.get(0).id()).matches("^fr_[a-z0-9]{12}$");
        assertThat(result.get(1).id()).matches("^fr_[a-z0-9]{12}$");
        assertThat(result.get(0).id()).isNotEqualTo(result.get(1).id());
        assertThat(result.get(2).id()).isEqualTo("fr_keep");
    }

    @Test
    void rule_ids_must_be_unique_within_the_list() {
        assertInvalid(List.of(force("fr_a", true), force("fr_a", false)), "Duplicated rule id 'fr_a'");
    }

    @Test
    void at_most_one_hundred_rules_are_accepted() {
        List<Rule> hundred = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add(force("fr_" + i, true));
        }
        assertThat(validator.validate(ValueType.BOOLEAN, hundred, attributes, groups)).hasSize(100);

        hundred.add(force("fr_100", true));
        assertInvalid(hundred, "at most 100 rules");
    }

    @Test
    void null_rules_are_rejected_with_their_position() {
        assertInvalid(Arrays.asList(force("fr_a", true), null), "rules[1] is null");
    }

    @Test
    void rule_values_must_match_the_feature_type() {
        assertInvalid(List.of(new ForceRule("fr_a", null, true, null, null, JSON.textNode("yes"))), "rules[0]: value is not a valid BOOLEAN");
        assertThat(validator.validate(ValueType.STRING, List.of(new ForceRule("fr_a", null, true, null, null, JSON.textNode("yes"))),
                attributes, groups)).hasSize(1);
    }

    @Test
    void invalid_value_errors_carry_their_message_code() {
        assertThatThrownBy(() -> validator.validate(ValueType.NUMBER, List.of(force("fr_a", true)), attributes, groups))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.INVALID_VALUE));
    }

    @Test
    void conditions_may_only_reference_declared_attributes() {
        Rule rule = new ForceRule("fr_a", null, true, JSON.objectNode().put("plan", "gold"), null, JSON.booleanNode(true));

        assertThatThrownBy(() -> validator.validate(ValueType.BOOLEAN, List.of(rule), attributes, groups))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.INVALID_CONDITION));
    }

    @Test
    void saved_groups_must_exist() {
        assertInvalid(List.of(new ForceRule("fr_a", null, true, null, List.of("vips", "ghosts"), JSON.booleanNode(true))),
                "unknown saved group 'ghosts'");
        assertThat(validator.validate(ValueType.BOOLEAN,
                List.of(new ForceRule("fr_a", null, true, null, List.of("vips"), JSON.booleanNode(true))), attributes, groups)).hasSize(1);
    }

    @Test
    void rollout_coverage_must_be_between_zero_and_one_inclusive() {
        assertThat(validator.validate(ValueType.BOOLEAN, List.of(rollout(0.0, "userId"), rollout(1.0, "userId")), attributes, groups)).hasSize(2);

        assertInvalid(List.of(rollout(-0.01, "userId")), "coverage must be between 0 and 1");
        assertInvalid(List.of(rollout(1.01, "userId")), "coverage must be between 0 and 1");
        assertInvalid(List.of(rollout(Double.NaN, "userId")), "coverage must be between 0 and 1");
    }

    @Test
    void rollout_buckets_by_a_declared_string_or_number_attribute() {
        assertThat(validator.validate(ValueType.BOOLEAN, List.of(rollout(0.5, "userId"), rollout(0.5, "accountNumber")), attributes, groups))
                .hasSize(2);

        assertInvalid(List.of(rollout(0.5, "ghost")), "unknown hashAttribute 'ghost'");
        assertInvalid(List.of(rollout(0.5, null)), "unknown hashAttribute 'null'");
        assertInvalid(List.of(rollout(0.5, "vip")), "hashAttribute must be a STRING or NUMBER attribute");
    }

    @Test
    void errors_name_the_index_of_the_offending_rule() {
        assertInvalid(List.of(force("fr_ok", true), rollout(2, "userId")), "rules[1]: coverage");
    }

    private void assertInvalid(List<Rule> rules, String message) {
        assertThatThrownBy(() -> validator.validate(ValueType.BOOLEAN, rules, attributes, groups))
                .isInstanceOf(ValidationException.class).hasMessageContaining(message);
    }

    private static Rule force(String id, boolean value) {
        return new ForceRule(id, null, true, null, null, JSON.booleanNode(value));
    }

    private static Rule rollout(double coverage, String hashAttribute) {
        return new RolloutRule(null, null, true, null, null, JSON.booleanNode(true), coverage, hashAttribute);
    }

    private static Attribute attribute(String key, AttributeDatatype datatype) {
        return new Attribute(key, datatype, null, false, false, List.of(), false, Instant.now(), Instant.now(), 0L);
    }
}
