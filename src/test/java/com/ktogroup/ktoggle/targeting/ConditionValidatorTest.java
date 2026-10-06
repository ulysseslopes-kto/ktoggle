package com.ktogroup.ktoggle.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionValidatorTest {

    private static final Set<String> ATTRIBUTES = Set.of("userId", "country", "age", "tags", "appVersion", "user");

    private final ConditionValidator validator = new ConditionValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"country\":\"BR\"}",
            "{\"country\":{\"$in\":[\"BR\",\"PT\"]},\"age\":{\"$gte\":18}}",
            "{\"$or\":[{\"country\":\"BR\"},{\"$and\":[{\"age\":{\"$lt\":30}},{\"userId\":{\"$exists\":true}}]}]}",
            "{\"$not\":{\"country\":\"AR\"}}",
            "{\"tags\":{\"$elemMatch\":{\"$eq\":\"vip\"}},\"appVersion\":{\"$vgte\":\"2.3.0\"}}",
            "{\"tags\":{\"$size\":{\"$gt\":2}},\"userId\":{\"$regex\":\"^abc\"}}",
            "{\"user.country\":\"BR\",\"age\":{\"$not\":{\"$lt\":18}},\"country\":{\"$type\":\"string\"}}"
    })
    void accepts_supported_conditions(String condition) {
        assertThatCode(() -> validator.validate(json(condition), ATTRIBUTES)).doesNotThrowAnyException();
    }

    @Test
    void null_condition_matches_everyone() {
        assertThatCode(() -> validator.validate(null, ATTRIBUTES)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"unknownAttr\":1}",
            "{\"country\":{\"$inGroup\":\"vips\"}}",
            "{\"country\":{\"$in\":\"BR\"}}",
            "{\"$or\":{\"country\":\"BR\"}}",
            "{\"$where\":\"1\"}",
            "{\"userId\":{\"$regex\":\"[unclosed\"}}",
            "{\"age\":{\"$gt\":null}}",
            "{\"country\":{\"$type\":\"date\"}}",
            "{\"appVersion\":{\"$vgt\":2}}"
    })
    void rejects_invalid_conditions(String condition) {
        assertThatThrownBy(() -> validator.validate(json(condition), ATTRIBUTES)).isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(a+)+$", "^(\\w*x)*y", "(?:a|b+){2,}", "((ab)*c)+", "([a-z]+)*@"})
    void rejects_regular_expressions_prone_to_catastrophic_backtracking(String regex) {
        assertThatThrownBy(() -> validator.validate(regexCondition(regex), ATTRIBUTES))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.getData().toString()).contains("nested quantifiers"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"^abc", "^[\\w.+-]+@[\\w-]+\\.[a-z]{2,}$", "(ab)+c", "(a|b)*", "[(a+)]+", "\\(a+\\)+", "^(BR|PT)-\\d+$"})
    void accepts_regular_expressions_without_nested_repetition(String regex) {
        assertThatCode(() -> validator.validate(regexCondition(regex), ATTRIBUTES)).doesNotThrowAnyException();
    }

    @Test
    void rejects_overly_long_regular_expressions() {
        assertThatThrownBy(() -> validator.validate(regexCondition("a".repeat(ConditionValidator.MAX_REGEX_LENGTH + 1)), ATTRIBUTES))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getData().toString()).contains("longer than"));
    }

    private JsonNode regexCondition(String regex) {
        return objectMapper.createObjectNode().set("userId", objectMapper.createObjectNode().put("$regex", regex));
    }

    @Test
    void reports_every_error_with_its_path() {
        assertThatThrownBy(() -> validator.validate(json("{\"nope\":1,\"age\":{\"$bogus\":1}}"), ATTRIBUTES))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat((List<?>) e.getData())
                        .hasSize(2)
                        .anySatisfy(error -> assertThat(error.toString()).contains("$.nope"))
                        .anySatisfy(error -> assertThat(error.toString()).contains("$.age.$bogus")));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
