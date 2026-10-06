package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.attribute.AttributeService.AttributeCommand;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.ExperimentRule;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.Prerequisite;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.feature.RuleSchedule;
import com.ktogroup.ktoggle.feature.ValueType;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Translates GrowthBook REST API objects into ktoggle's model, with the exact semantics GrowthBook's SDK payload has:
 * rollout seeds (the rule id) and experiment hash versions (v1 unless set) are kept, so after a migration every user
 * gets the same value and the same experiment variation. Anything without an equivalent is reported, never dropped
 * silently.
 */
@Component
@RequiredArgsConstructor
public class GrowthBookMapper {

    private final ObjectMapper objectMapper;

    public record FeaturePlan(String key, String gbProjectId, ValueType valueType, JsonNode defaultValue, String description,
                              String owner, List<String> tags, boolean archived, List<Prerequisite> prerequisites,
                              Map<String, EnvironmentSettings> environments, List<String> warnings) {
    }

    public Optional<ValueType> valueType(String gbValueType) {
        return switch (gbValueType == null ? "" : gbValueType) {
            case "boolean" -> Optional.of(ValueType.BOOLEAN);
            case "string" -> Optional.of(ValueType.STRING);
            case "number" -> Optional.of(ValueType.NUMBER);
            case "json" -> Optional.of(ValueType.JSON);
            default -> Optional.empty();
        };
    }

    /**
     * @param environmentKeys GrowthBook environment id &rarr; ktoggle environment key (environments missing here are skipped)
     * @param experiments     lookup for experiment-ref rules
     */
    public FeaturePlan feature(JsonNode gb, Map<String, String> environmentKeys, Function<String, Optional<JsonNode>> experiments) {
        ValueType type = valueType(gb.path("valueType").asText()).orElseThrow(() ->
                new IllegalArgumentException("unsupported value type '" + gb.path("valueType").asText() + "'"));
        List<String> warnings = new ArrayList<>();
        Map<String, EnvironmentSettings> environments = new LinkedHashMap<>();
        gb.path("environments").properties().forEach(entry -> {
            String target = environmentKeys.get(entry.getKey());
            if (target == null) {
                warnings.add("environment '" + entry.getKey() + "' is not mapped and was skipped");
                return;
            }
            List<Rule> rules = new ArrayList<>();
            for (JsonNode rule : entry.getValue().path("rules")) {
                try {
                    rules.add(rule(rule, type, experiments));
                } catch (IllegalArgumentException e) {
                    warnings.add("%s: rule %s skipped: %s".formatted(entry.getKey(), rule.path("id").asText("?"), e.getMessage()));
                }
            }
            environments.put(target, new EnvironmentSettings(entry.getValue().path("enabled").asBoolean(false), rules));
        });
        return new FeaturePlan(gb.path("id").asText(), blankToNull(gb.path("project").asText(null)), type,
                value(type, gb.path("defaultValue")), blankToNull(gb.path("description").asText(null)),
                blankToNull(gb.path("owner").asText(null)), strings(gb.path("tags")), gb.path("archived").asBoolean(false),
                prerequisites(gb.path("prerequisites")), environments, List.copyOf(warnings));
    }

    Rule rule(JsonNode gb, ValueType type, Function<String, Optional<JsonNode>> experiments) {
        String id = blankToNull(gb.path("id").asText(null));
        String description = blankToNull(gb.path("description").asText(null));
        boolean enabled = gb.path("enabled").asBoolean(true);
        JsonNode condition = condition(gb.path("condition"));
        Targeting groups = targeting(gb);
        RuleSchedule schedule = schedule(gb.path("scheduleRules"));
        List<Prerequisite> prerequisites = prerequisites(gb.path("prerequisites"));
        return switch (gb.path("type").asText()) {
            case "force" -> new ForceRule(id, description, enabled, condition, groups.all(), value(type, gb.path("value")), schedule,
                    prerequisites, groups.any(), groups.none());
            case "rollout" -> new RolloutRule(id, description, enabled, condition, groups.all(), value(type, gb.path("value")),
                    gb.path("coverage").asDouble(1), gb.path("hashAttribute").asText("id"), schedule, prerequisites, groups.any(),
                    groups.none(), Optional.ofNullable(blankToNull(gb.path("seed").asText(null))).orElse(id));
            case "experiment" -> inlineExperiment(gb, id, description, enabled, condition, groups, schedule, prerequisites, type);
            case "experiment-ref" -> experimentRef(gb, id, description, enabled, condition, groups, schedule, prerequisites, type,
                    experiments);
            default -> throw new IllegalArgumentException("rule type '" + gb.path("type").asText() + "' has no ktoggle equivalent");
        };
    }

    private Rule inlineExperiment(JsonNode gb, String id, String description, boolean enabled, JsonNode condition, Targeting groups,
                                  RuleSchedule schedule, List<Prerequisite> prerequisites, ValueType type) {
        if (gb.hasNonNull("namespace") && gb.path("namespace").path("enabled").asBoolean(false)) {
            throw new IllegalArgumentException("namespaces are not supported");
        }
        List<ExperimentRule.Variation> variations = new ArrayList<>();
        JsonNode values = gb.path("values");
        for (int i = 0; i < values.size(); i++) {
            JsonNode v = values.get(i);
            variations.add(new ExperimentRule.Variation(String.valueOf(i), blankToNull(v.path("name").asText(null)),
                    value(type, v.path("value")), v.path("weight").asDouble()));
        }
        return new ExperimentRule(id, description, enabled, condition, groups.all(), gb.path("trackingKey").asText(id),
                gb.path("hashAttribute").asText("id"), gb.path("coverage").asDouble(1), variations,
                gb.path("hashVersion").asInt(1), blankToNull(gb.path("seed").asText(null)), schedule, prerequisites, groups.any(),
                groups.none());
    }

    private Rule experimentRef(JsonNode gb, String id, String description, boolean enabled, JsonNode condition, Targeting groups,
                               RuleSchedule schedule, List<Prerequisite> prerequisites, ValueType type,
                               Function<String, Optional<JsonNode>> experiments) {
        String experimentId = gb.path("experimentId").asText();
        JsonNode experiment = experiments.apply(experimentId).orElseThrow(() ->
                new IllegalArgumentException("experiment " + experimentId + " not found"));
        if (!"running".equals(experiment.path("status").asText())) {
            throw new IllegalArgumentException("experiment " + experimentId + " is " + experiment.path("status").asText()
                    + " (only running experiments are imported)");
        }
        JsonNode phases = experiment.path("phases");
        JsonNode phase = phases.isEmpty() ? objectMapper.createObjectNode() : phases.get(phases.size() - 1);
        Map<String, JsonNode> valueByVariation = new LinkedHashMap<>();
        gb.path("variations").forEach(v -> valueByVariation.put(v.path("variationId").asText(), value(type, v.path("value"))));
        Map<String, Double> weightByVariation = new LinkedHashMap<>();
        phase.path("trafficSplit").forEach(t -> weightByVariation.put(t.path("variationId").asText(), t.path("weight").asDouble()));
        List<ExperimentRule.Variation> variations = new ArrayList<>();
        for (JsonNode v : experiment.path("variations")) {
            String variationId = v.path("variationId").asText();
            if (!valueByVariation.containsKey(variationId)) {
                throw new IllegalArgumentException("variation " + variationId + " has no value in the rule");
            }
            variations.add(new ExperimentRule.Variation(v.path("key").asText(String.valueOf(variations.size())),
                    blankToNull(v.path("name").asText(null)), valueByVariation.get(variationId),
                    weightByVariation.getOrDefault(variationId, 0.0)));
        }
        JsonNode phaseCondition = condition(phase.path("targetingCondition"));
        if (phaseCondition != null && condition != null) {
            throw new IllegalArgumentException("both the rule and the experiment phase have a condition");
        }
        return new ExperimentRule(id, description == null ? blankToNull(experiment.path("name").asText(null)) : description, enabled,
                condition == null ? phaseCondition : condition, groups.all(), experiment.path("trackingKey").asText(experimentId),
                experiment.path("hashAttribute").asText("id"), phase.path("coverage").asDouble(1), variations,
                experiment.path("hashVersion").asInt(1), blankToNull(phase.path("seed").asText(null)), schedule, prerequisites,
                groups.any(), groups.none());
    }

    public Optional<AttributeCommand> attribute(JsonNode gb, List<String> warnings) {
        String key = gb.path("property").asText();
        String datatype = gb.path("datatype").asText();
        boolean secure = datatype.startsWith("secure");
        AttributeDatatype mapped = switch (datatype) {
            case "string", "secureString" -> AttributeDatatype.STRING;
            case "number" -> AttributeDatatype.NUMBER;
            case "boolean" -> AttributeDatatype.BOOLEAN;
            case "string[]", "secureString[]" -> AttributeDatatype.STRING_ARRAY;
            case "number[]" -> AttributeDatatype.NUMBER_ARRAY;
            case "enum" -> AttributeDatatype.ENUM;
            default -> null;
        };
        if (mapped == null) {
            warnings.add("attribute '" + key + "': datatype '" + datatype + "' has no ktoggle equivalent");
            return Optional.empty();
        }
        List<String> enumValues = mapped == AttributeDatatype.ENUM
                ? List.of(gb.path("enum").asText("").split("\\s*,\\s*")).stream().filter(s -> !s.isBlank()).toList()
                : List.of();
        return Optional.of(new AttributeCommand(key, mapped, blankToNull(gb.path("description").asText(null)),
                gb.path("hashAttribute").asBoolean(false), secure, enumValues, gb.path("archived").asBoolean(false)));
    }

    public SavedGroupCommand savedGroup(JsonNode gb) {
        boolean list = "list".equals(gb.path("type").asText());
        List<JsonNode> values = new ArrayList<>();
        gb.path("values").forEach(v -> values.add(TextNode.valueOf(v.asText())));
        return new SavedGroupCommand(gb.path("id").asText(), gb.path("name").asText(gb.path("id").asText()),
                blankToNull(gb.path("description").asText(null)), list ? SavedGroupType.LIST : SavedGroupType.CONDITION,
                list ? gb.path("attributeKey").asText() : null, list ? values : null, list ? null : condition(gb.path("condition")));
    }

    /** GrowthBook sends values as strings: raw for strings, JSON for the other types. */
    JsonNode value(ValueType type, JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return NullNode.getInstance();
        }
        String text = raw.isTextual() ? raw.asText() : raw.toString();
        try {
            return switch (type) {
                case STRING -> TextNode.valueOf(text);
                case BOOLEAN -> BooleanNode.valueOf(Boolean.parseBoolean(text.strip()));
                case NUMBER -> number(text.strip());
                case JSON -> objectMapper.readTree(text);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid " + type + " value '" + text + "'");
        }
    }

    private static JsonNode number(String text) {
        BigDecimal decimal = new BigDecimal(text);
        if (decimal.scale() <= 0 && decimal.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
            return IntNode.valueOf(decimal.intValue());
        }
        if (decimal.scale() <= 0) {
            return LongNode.valueOf(decimal.longValue());
        }
        return DecimalNode.valueOf(decimal);
    }

    private JsonNode condition(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        try {
            JsonNode parsed = raw.isTextual() ? (raw.asText().isBlank() ? null : objectMapper.readTree(raw.asText())) : raw;
            return parsed == null || (parsed.isObject() && parsed.isEmpty()) ? null : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid condition " + raw);
        }
    }

    private record Targeting(List<String> all, List<String> any, List<String> none) {
    }

    /** {@code savedGroups: [{match, ids}]} (current API) or {@code savedGroupTargeting: [{matchType, savedGroups}]}. */
    private static Targeting targeting(JsonNode rule) {
        List<String> all = new ArrayList<>();
        List<String> any = new ArrayList<>();
        List<String> none = new ArrayList<>();
        boolean current = rule.path("savedGroups").isArray() && !rule.path("savedGroups").isEmpty();
        JsonNode blocks = current ? rule.path("savedGroups") : rule.path("savedGroupTargeting");
        for (JsonNode block : blocks) {
            String match = current ? block.path("match").asText() : block.path("matchType").asText();
            List<String> ids = strings(current ? block.path("ids") : block.path("savedGroups"));
            switch (match) {
                case "all" -> all.addAll(ids);
                case "any" -> {
                    if (!any.isEmpty()) {
                        throw new IllegalArgumentException("several 'any' saved-group blocks are not supported");
                    }
                    any.addAll(ids);
                }
                case "none" -> none.addAll(ids);
                default -> throw new IllegalArgumentException("saved-group match '" + match + "' is not supported");
            }
        }
        return new Targeting(all, any, none);
    }

    /** {@code [{enabled: true, timestamp}, {enabled: false, timestamp}]}: rule on at the first, off at the second. */
    private static RuleSchedule schedule(JsonNode scheduleRules) {
        Instant start = null;
        Instant end = null;
        for (JsonNode s : scheduleRules) {
            if (!s.hasNonNull("timestamp")) {
                continue;
            }
            Instant at = Instant.parse(s.path("timestamp").asText());
            if (s.path("enabled").asBoolean()) {
                start = at;
            } else {
                end = at;
            }
        }
        return start == null && end == null ? null : new RuleSchedule(start, end);
    }

    private List<Prerequisite> prerequisites(JsonNode raw) {
        List<Prerequisite> prerequisites = new ArrayList<>();
        for (JsonNode p : raw) {
            if (p.isTextual()) {
                prerequisites.add(new Prerequisite(p.asText(), objectMapper.createObjectNode().set("value",
                        objectMapper.createObjectNode().put("$exists", true))));
            } else {
                JsonNode condition = condition(p.path("condition"));
                prerequisites.add(new Prerequisite(p.path("id").asText(), condition == null
                        ? objectMapper.createObjectNode().set("value", objectMapper.createObjectNode().put("$exists", true))
                        : condition));
            }
        }
        return prerequisites;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
