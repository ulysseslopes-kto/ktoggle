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
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Validates a rule list against the feature type, declared attributes and saved groups; assigns missing ids. */
@Component
@RequiredArgsConstructor
public class RuleValidator {

    static final int MAX_RULES = 100;
    static final int MAX_VARIATIONS = 20;
    private static final double WEIGHT_TOLERANCE = 0.0001;
    private static final Pattern TRACKING_KEY = Pattern.compile("^[a-zA-Z0-9_.:-]{1,150}$");
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
        rule.referencedSavedGroups().forEach(group -> {
            if (!savedGroupKeys.contains(group)) {
                throw ValidationException.of("%s: unknown saved group '%s'".formatted(at, group));
            }
        });
        Set<String> inAll = Set.copyOf(rule.savedGroups());
        rule.savedGroupsNone().stream().filter(g -> inAll.contains(g) || rule.savedGroupsAny().contains(g)).findFirst()
                .ifPresent(group -> {
                    throw ValidationException.of("%s: saved group '%s' is both required and excluded".formatted(at, group));
                });
        RuleSchedule schedule = rule.schedule();
        if (schedule != null && schedule.startsAt() != null && schedule.endsAt() != null
                && !schedule.endsAt().isAfter(schedule.startsAt())) {
            throw ValidationException.of("%s: the schedule must end after it starts".formatted(at));
        }
        switch (rule) {
            case ForceRule ignored -> {
                // value and targeting already validated
            }
            case RolloutRule rollout -> {
                validateCoverage(at, rollout.coverage());
                validateHashAttribute(at, rollout.hashAttribute(), attributes);
            }
            case ExperimentRule experiment -> validateExperiment(at, experiment, valueType, attributes);
        }
    }

    private static void validateExperiment(String at, ExperimentRule experiment, ValueType valueType,
                                           Map<String, Attribute> attributes) {
        if (experiment.trackingKey() == null || !TRACKING_KEY.matcher(experiment.trackingKey()).matches()) {
            throw ValidationException.of("%s: trackingKey is required (letters, digits and _ . : -, up to 150 chars)".formatted(at));
        }
        validateCoverage(at, experiment.coverage());
        validateHashAttribute(at, experiment.hashAttribute(), attributes);
        if (experiment.hashVersion() != 1 && experiment.hashVersion() != 2) {
            throw ValidationException.of("%s: hashVersion must be 1 or 2".formatted(at));
        }
        List<ExperimentRule.Variation> variations = experiment.variations();
        if (variations.size() < 2 || variations.size() > MAX_VARIATIONS) {
            throw ValidationException.of("%s: an experiment needs between 2 and %d variations".formatted(at, MAX_VARIATIONS));
        }
        Set<String> keys = new HashSet<>();
        double total = 0;
        for (int v = 0; v < variations.size(); v++) {
            ExperimentRule.Variation variation = variations.get(v);
            String where = "%s.variations[%d]".formatted(at, v);
            if (variation.key() == null || variation.key().isBlank() || !keys.add(variation.key())) {
                throw ValidationException.of("%s: variation keys must be unique and non-empty".formatted(where));
            }
            if (!valueType.accepts(variation.value())) {
                throw new ValidationException(MessageCode.INVALID_VALUE, "%s: value is not a valid %s".formatted(where, valueType));
            }
            if (variation.weight() < 0 || Double.isNaN(variation.weight())) {
                throw ValidationException.of("%s: weight must be zero or positive".formatted(where));
            }
            total += variation.weight();
        }
        if (Math.abs(total - 1.0) > WEIGHT_TOLERANCE) {
            throw ValidationException.of("%s: variation weights must add up to 100%% (got %.2f%%)".formatted(at, total * 100));
        }
    }

    private static void validateCoverage(String at, double coverage) {
        if (coverage < 0 || coverage > 1 || Double.isNaN(coverage)) {
            throw ValidationException.of("%s: coverage must be between 0 and 1".formatted(at));
        }
    }

    private static void validateHashAttribute(String at, String hashAttribute, Map<String, Attribute> attributes) {
        Attribute hash = attributes.get(hashAttribute);
        if (hash == null) {
            throw ValidationException.of("%s: unknown hashAttribute '%s'".formatted(at, hashAttribute));
        }
        if (hash.datatype() != AttributeDatatype.STRING && hash.datatype() != AttributeDatatype.NUMBER) {
            throw ValidationException.of("%s: hashAttribute must be a STRING or NUMBER attribute".formatted(at));
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
