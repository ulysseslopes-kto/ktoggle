package com.ktogroup.ktoggle.targeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Validates targeting conditions written in the MongoDB-like syntax evaluated by the GrowthBook SDKs.
 * Only operators understood by every SDK in use at KTO (Java 0.10.x, JS 1.x) are accepted — notably
 * {@code $inGroup}/{@code $notInGroup} are rejected because saved groups are inlined at compile time.
 */
@Component
public class ConditionValidator {

    private static final Set<String> LOGICAL = Set.of("$and", "$or", "$nor");
    private static final Set<String> COMPARISON = Set.of("$eq", "$ne", "$lt", "$lte", "$gt", "$gte");
    private static final Set<String> VERSION = Set.of("$veq", "$vne", "$vgt", "$vgte", "$vlt", "$vlte");
    private static final Set<String> ARRAY_ARG = Set.of("$in", "$nin", "$all");
    private static final Set<String> TYPES = Set.of("string", "number", "boolean", "array", "object", "null");

    /**
     * @param condition     the condition (null or {} means "always matches")
     * @param knownAttributes attribute keys that can be referenced; the root segment of dotted paths is checked
     */
    public void validate(JsonNode condition, Set<String> knownAttributes) {
        if (condition == null || condition.isNull()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        if (!condition.isObject()) {
            errors.add("condition must be a JSON object");
        } else {
            validateCondition(condition, "$", knownAttributes, errors);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(MessageCode.INVALID_CONDITION, "Invalid targeting condition", errors);
        }
    }

    private void validateCondition(JsonNode condition, String path, Set<String> knownAttributes, List<String> errors) {
        for (Iterator<Map.Entry<String, JsonNode>> it = condition.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            String here = path + "." + key;
            if (LOGICAL.contains(key)) {
                if (!value.isArray()) {
                    errors.add(here + ": must be an array of conditions");
                    continue;
                }
                for (int i = 0; i < value.size(); i++) {
                    JsonNode child = value.get(i);
                    if (child.isObject()) {
                        validateCondition(child, here + "[" + i + "]", knownAttributes, errors);
                    } else {
                        errors.add(here + "[" + i + "]: must be a condition object");
                    }
                }
            } else if ("$not".equals(key)) {
                if (value.isObject()) {
                    validateCondition(value, here, knownAttributes, errors);
                } else {
                    errors.add(here + ": must be a condition object");
                }
            } else if (key.startsWith("$")) {
                errors.add(here + ": unsupported logical operator");
            } else {
                String root = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
                if (!knownAttributes.contains(root)) {
                    errors.add(here + ": unknown attribute '" + root + "' (declare it under /admin/v1/attributes)");
                }
                validateAttributeValue(value, here, knownAttributes, errors);
            }
        }
    }

    private void validateAttributeValue(JsonNode value, String path, Set<String> knownAttributes, List<String> errors) {
        if (!isOperatorObject(value)) {
            return; // literal equality, including object literals
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String op = field.getKey();
            JsonNode arg = field.getValue();
            String here = path + "." + op;
            if (COMPARISON.contains(op)) {
                requireThat(arg.isValueNode() && !arg.isNull(), here, "must be a string, number or boolean", errors);
            } else if (VERSION.contains(op)) {
                requireThat(arg.isTextual(), here, "must be a version string", errors);
            } else if (ARRAY_ARG.contains(op)) {
                requireThat(arg.isArray(), here, "must be an array", errors);
            } else if ("$exists".equals(op)) {
                requireThat(arg.isBoolean(), here, "must be a boolean", errors);
            } else if ("$type".equals(op)) {
                requireThat(arg.isTextual() && TYPES.contains(arg.asText()), here, "must be one of " + TYPES, errors);
            } else if ("$regex".equals(op)) {
                validateRegex(arg, here, errors);
            } else if ("$size".equals(op)) {
                if (!arg.isNumber()) {
                    validateAttributeValue(arg, here, knownAttributes, errors);
                }
            } else if ("$elemMatch".equals(op)) {
                if (!arg.isObject()) {
                    errors.add(here + ": must be an object");
                } else if (isOperatorObject(arg)) {
                    validateAttributeValue(arg, here, knownAttributes, errors);
                } else {
                    validateElementCondition(arg, here, knownAttributes, errors);
                }
            } else if ("$not".equals(op)) {
                validateAttributeValue(arg, here, knownAttributes, errors);
            } else {
                errors.add(here + ": unsupported operator");
            }
        }
    }

    /** Conditions inside {@code $elemMatch} address fields of the array element, not top-level attributes. */
    private void validateElementCondition(JsonNode condition, String path, Set<String> knownAttributes, List<String> errors) {
        for (Iterator<Map.Entry<String, JsonNode>> it = condition.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            validateAttributeValue(field.getValue(), path + "." + field.getKey(), knownAttributes, errors);
        }
    }

    private static boolean isOperatorObject(JsonNode value) {
        if (!value.isObject() || value.isEmpty()) {
            return false;
        }
        for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
            if (!names.next().startsWith("$")) {
                return false;
            }
        }
        return true;
    }

    private static void validateRegex(JsonNode arg, String path, List<String> errors) {
        if (!arg.isTextual()) {
            errors.add(path + ": must be a string");
            return;
        }
        try {
            Pattern.compile(arg.asText());
        } catch (PatternSyntaxException e) {
            errors.add(path + ": invalid regular expression (" + e.getDescription() + ")");
        }
    }

    private static void requireThat(boolean condition, String path, String message, List<String> errors) {
        if (!condition) {
            errors.add(path + ": " + message);
        }
    }
}
