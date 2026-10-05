package com.ktogroup.ktoggle.feature;

import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Validates a rule list against the feature type, declared attributes and saved groups; assigns missing ids. */
@Component
@RequiredArgsConstructor
public class RuleValidator {

    static final int MAX_RULES = 100;
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConditionValidator conditionValidator;

    public List<Rule> validate(ValueType valueType, List<Rule> rules, Map<String, Attribute> attributes,
                               Set<String> savedGroupKeys) {
        if (rules == null) {
            return List.of();
        }
        if (rules.size() > MAX_RULES) {
            throw ValidationException.of("A feature can have at most %d rules per environment".formatted(MAX_RULES));
        }
        Set<String> ids = new HashSet<>();
        List<Rule> result = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (rule == null) {
                throw ValidationException.of("rules[%d] is null".formatted(i));
            }
            Rule withId = rule.id() == null || rule.id().isBlank() ? rule.withId(newRuleId()) : rule;
            if (!ids.add(withId.id())) {
                throw ValidationException.of("Duplicated rule id '%s'".formatted(withId.id()));
            }
            validateRule(i, withId, valueType, attributes, savedGroupKeys);
            result.add(withId);
        }
        return List.copyOf(result);
    }

    private void validateRule(int index, Rule rule, ValueType valueType, Map<String, Attribute> attributes,
                              Set<String> savedGroupKeys) {
        String at = "rules[%d]".formatted(index);
        if (!valueType.accepts(rule.value())) {
            throw new ValidationException(MessageCode.INVALID_VALUE, "%s: value is not a valid %s".formatted(at, valueType));
        }
        conditionValidator.validate(rule.condition(), attributes.keySet());
        for (String group : rule.savedGroups()) {
            if (!savedGroupKeys.contains(group)) {
                throw ValidationException.of("%s: unknown saved group '%s'".formatted(at, group));
            }
        }
        if (rule instanceof RolloutRule rollout) {
            if (rollout.coverage() < 0 || rollout.coverage() > 1 || Double.isNaN(rollout.coverage())) {
                throw ValidationException.of("%s: coverage must be between 0 and 1".formatted(at));
            }
            Attribute hash = attributes.get(rollout.hashAttribute());
            if (hash == null) {
                throw ValidationException.of("%s: unknown hashAttribute '%s'".formatted(at, rollout.hashAttribute()));
            }
            if (hash.datatype() != AttributeDatatype.STRING && hash.datatype() != AttributeDatatype.NUMBER) {
                throw ValidationException.of("%s: hashAttribute must be a STRING or NUMBER attribute".formatted(at));
            }
        }
    }

    /** Same shape as GrowthBook rule ids ({@code fr_...}). */
    static String newRuleId() {
        StringBuilder id = new StringBuilder("fr_");
        for (int i = 0; i < 12; i++) {
            id.append(ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)]);
        }
        return id.toString();
    }
}
