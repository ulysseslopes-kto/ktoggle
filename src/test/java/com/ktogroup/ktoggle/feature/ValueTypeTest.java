package com.ktogroup.ktoggle.feature;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ValueTypeTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void no_type_accepts_null_or_missing_values() {
        for (ValueType type : ValueType.values()) {
            assertThat(type.accepts(null)).isFalse();
            assertThat(type.accepts(NullNode.getInstance())).isFalse();
            assertThat(type.accepts(MissingNode.getInstance())).isFalse();
        }
    }

    @Test
    void boolean_accepts_only_booleans() {
        assertThat(ValueType.BOOLEAN.accepts(JSON.booleanNode(false))).isTrue();
        assertThat(ValueType.BOOLEAN.accepts(JSON.textNode("true"))).isFalse();
        assertThat(ValueType.BOOLEAN.accepts(JSON.numberNode(1))).isFalse();
    }

    @Test
    void string_accepts_only_text() {
        assertThat(ValueType.STRING.accepts(JSON.textNode(""))).isTrue();
        assertThat(ValueType.STRING.accepts(JSON.numberNode(1))).isFalse();
        assertThat(ValueType.STRING.accepts(JSON.objectNode())).isFalse();
    }

    @Test
    void number_accepts_finite_numbers_and_integers_up_to_the_largest_safe_integer() {
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(1.5))).isTrue();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(9_007_199_254_740_991L))).isTrue();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(-9_007_199_254_740_991L))).isTrue();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(9_007_199_254_740_992L))).isFalse();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(new BigDecimal("1e400")))).isFalse();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(Double.POSITIVE_INFINITY))).isFalse();
        assertThat(ValueType.NUMBER.accepts(JSON.numberNode(Double.NaN))).isFalse();
        assertThat(ValueType.NUMBER.accepts(JSON.textNode("1"))).isFalse();
    }

    @Test
    void json_accepts_any_non_null_value() {
        assertThat(ValueType.JSON.accepts(JSON.objectNode().put("a", 1))).isTrue();
        assertThat(ValueType.JSON.accepts(JSON.arrayNode())).isTrue();
        assertThat(ValueType.JSON.accepts(JSON.textNode("x"))).isTrue();
    }
}
