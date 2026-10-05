package com.ktogroup.ktoggle.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Three-way merge of a draft with the live feature, section by section — each metadata field and each environment
 * (enabled + ordered rules) is one section. A section changed only by the draft takes the draft's value, a section
 * changed only live keeps the live value, and a section changed differently on both sides is a conflict that must
 * be resolved explicitly. Nothing is ever overwritten silently.
 */
@Component
@RequiredArgsConstructor
public class DraftMerger {

    static final String ENVIRONMENT_PREFIX = "environments.";
    private static final List<String> FIELDS = List.of("defaultValue", "projectKey", "description", "owner", "tags", "archived");

    private final ObjectMapper objectMapper;

    public MergeResult merge(FeatureSnapshot base, FeatureSnapshot live, FeatureSnapshot draft) {
        Map<String, JsonNode> b = sections(base);
        Map<String, JsonNode> l = sections(live);
        Map<String, JsonNode> d = sections(draft);
        TreeSet<String> names = new TreeSet<>(l.keySet());
        names.addAll(d.keySet());
        names.addAll(b.keySet());

        Map<String, JsonNode> merged = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        List<SectionChange> changes = new ArrayList<>();
        for (String name : names) {
            JsonNode baseValue = b.getOrDefault(name, NullNode.getInstance());
            JsonNode liveValue = l.getOrDefault(name, NullNode.getInstance());
            JsonNode draftValue = d.getOrDefault(name, NullNode.getInstance());
            JsonNode result;
            if (Objects.equals(draftValue, baseValue) || Objects.equals(draftValue, liveValue)) {
                result = liveValue;
            } else if (Objects.equals(liveValue, baseValue)) {
                result = draftValue;
            } else {
                conflicts.add(name);
                result = draftValue;
            }
            merged.put(name, result);
            if (!Objects.equals(result, liveValue)) {
                changes.add(new SectionChange(name, liveValue, result));
            }
        }
        return new MergeResult(toSnapshot(live, merged), List.copyOf(conflicts), List.copyOf(changes));
    }

    /**
     * Resolves conflicts by taking one side for every conflicting section ({@code keepDraft} true = the draft's
     * value). Used by rebase, after the user saw the conflicts.
     */
    public FeatureSnapshot resolve(FeatureSnapshot base, FeatureSnapshot live, FeatureSnapshot draft, boolean keepDraft) {
        MergeResult result = merge(base, live, draft);
        if (keepDraft || result.conflicts().isEmpty()) {
            return result.merged();
        }
        Map<String, JsonNode> merged = sections(result.merged());
        Map<String, JsonNode> l = sections(live);
        result.conflicts().forEach(name -> merged.put(name, l.getOrDefault(name, NullNode.getInstance())));
        return toSnapshot(live, merged);
    }

    private Map<String, JsonNode> sections(FeatureSnapshot snapshot) {
        ObjectNode tree = objectMapper.valueToTree(snapshot);
        Map<String, JsonNode> sections = new LinkedHashMap<>();
        FIELDS.forEach(field -> sections.put(field, tree.path(field).isMissingNode() ? NullNode.getInstance() : tree.get(field)));
        tree.path("environments").properties().forEach(env -> sections.put(ENVIRONMENT_PREFIX + env.getKey(), env.getValue()));
        return sections;
    }

    private FeatureSnapshot toSnapshot(FeatureSnapshot template, Map<String, JsonNode> sections) {
        ObjectNode tree = objectMapper.createObjectNode();
        tree.put("key", template.key());
        tree.put("valueType", template.valueType().name());
        ObjectNode environments = tree.putObject("environments");
        sections.forEach((name, value) -> {
            if (name.startsWith(ENVIRONMENT_PREFIX)) {
                if (!value.isNull()) {
                    environments.set(name.substring(ENVIRONMENT_PREFIX.length()), value);
                }
            } else {
                tree.set(name, value);
            }
        });
        try {
            return objectMapper.treeToValue(tree, FeatureSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to rebuild merged snapshot", e);
        }
    }

    /**
     * @param merged    what would go live
     * @param conflicts sections changed both live and in the draft, differently
     * @param changes   sections whose merged value differs from the live one (the diff to review)
     */
    public record MergeResult(FeatureSnapshot merged, List<String> conflicts, List<SectionChange> changes) {
    }

    /** {@code live} is what SDKs get today, {@code proposed} what they would get after publishing. */
    public record SectionChange(String section, JsonNode live, JsonNode proposed) {

        public String environment() {
            return section.startsWith(ENVIRONMENT_PREFIX) ? section.substring(ENVIRONMENT_PREFIX.length()) : null;
        }
    }
}
