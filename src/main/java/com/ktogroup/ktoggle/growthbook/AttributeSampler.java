package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Simulated users for the shadow comparison. Values come from the payloads themselves (every literal a condition
 * compares with, numbers just around each threshold, enum values) plus fresh ids for hashing, so each rule is exercised
 * on both sides of its condition and across rollout buckets. Deterministic for a given seed, so a divergence can be
 * reproduced. Never uses real user data.
 */
public final class AttributeSampler {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final double ABSENT = 0.12;

    private AttributeSampler() {
    }

    public static List<ObjectNode> samples(Collection<Attribute> catalog, Collection<JsonNode> payloads, int count, long seed) {
        Map<String, Set<JsonNode>> candidates = new LinkedHashMap<>();
        Set<String> hashAttributes = new LinkedHashSet<>();
        for (Attribute attribute : catalog) {
            Set<JsonNode> values = candidates.computeIfAbsent(attribute.key(), k -> new LinkedHashSet<>());
            if (attribute.datatype() == AttributeDatatype.BOOLEAN) {
                values.add(JSON.booleanNode(true));
                values.add(JSON.booleanNode(false));
            }
            attribute.enumValues().forEach(v -> values.add(JSON.textNode(v)));
            if (attribute.hashAttribute()) {
                hashAttributes.add(attribute.key());
            }
        }
        for (JsonNode payload : payloads) {
            JsonNode groups = payload.path("savedGroups");
            payload.path("features").forEach(feature -> feature.path("rules").forEach(rule -> {
                collect(rule.path("condition"), candidates, groups);
                rule.path("parentConditions").forEach(p -> collect(p.path("condition"), candidates, groups));
                if (rule.hasNonNull("hashAttribute")) {
                    hashAttributes.add(rule.path("hashAttribute").asText());
                }
            }));
        }
        hashAttributes.forEach(key -> candidates.computeIfAbsent(key, k -> new LinkedHashSet<>()));

        Random random = new Random(seed);
        List<String> keys = new ArrayList<>(candidates.keySet());
        List<ObjectNode> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ObjectNode sample = JSON.objectNode();
            for (String key : keys) {
                if (hashAttributes.contains(key) && random.nextDouble() > ABSENT / 4) {
                    sample.put(key, "shadow-" + Long.toHexString(random.nextLong()));
                    continue;
                }
                List<JsonNode> values = new ArrayList<>(candidates.get(key));
                if (values.isEmpty() || random.nextDouble() < ABSENT) {
                    continue;
                }
                sample.set(key, values.get(random.nextInt(values.size())));
            }
            samples.add(sample);
        }
        return samples;
    }

    /** Walks a condition: {@code {attr: literal}}, {@code {attr: {$op: x}}}, {@code $and/$or/$nor/$not}. */
    static void collect(JsonNode condition, Map<String, Set<JsonNode>> candidates, JsonNode groups) {
        if (condition == null || !condition.isObject()) {
            if (condition != null && condition.isArray()) {
                condition.forEach(c -> collect(c, candidates, groups));
            }
            return;
        }
        condition.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (field.startsWith("$")) {
                collect(value, candidates, groups);
                return;
            }
            Set<JsonNode> values = candidates.computeIfAbsent(field, k -> new LinkedHashSet<>());
            if (!value.isObject()) {
                addLiteral(value, values);
                return;
            }
            value.properties().forEach(op -> {
                JsonNode operand = op.getValue();
                switch (op.getKey()) {
                    case "$eq", "$ne", "$veq", "$vne" -> addLiteral(operand, values);
                    case "$in", "$nin", "$all" -> operand.forEach(v -> addLiteral(v, values));
                    case "$gt", "$gte", "$lt", "$lte" -> {
                        if (operand.isNumber()) {
                            double n = operand.asDouble();
                            values.add(JSON.numberNode(n));
                            values.add(JSON.numberNode(n + 1));
                            values.add(JSON.numberNode(n - 1));
                        } else {
                            addLiteral(operand, values);
                        }
                    }
                    case "$vgt", "$vgte", "$vlt", "$vlte" -> addLiteral(operand, values);
                    // GrowthBook payloads may reference saved groups instead of inlining them
                    case "$inGroup", "$notInGroup" -> groups.path(operand.asText()).forEach(v -> addLiteral(v, values));
                    case "$not", "$elemMatch" -> {
                        ObjectNode nested = JSON.objectNode();
                        nested.set(field, operand);
                        collect(nested, candidates, groups);
                    }
                    default -> {
                        // $exists, $regex, $type, $size: presence/absence is already sampled
                    }
                }
            });
        });
    }

    private static void addLiteral(JsonNode value, Set<JsonNode> values) {
        if (value != null && (value.isValueNode()) && !value.isNull()) {
            values.add(value);
        }
    }
}
