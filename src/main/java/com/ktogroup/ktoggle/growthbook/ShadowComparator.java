package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.GBContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compares what GrowthBook and ktoggle would give each simulated user, feature by feature, using the official SDK on
 * both payloads. The payloads are never compared as text: GrowthBook may reference saved groups ({@code $inGroup}) where
 * ktoggle inlines them, and rule ids and ordering of keys differ; only the resulting values matter.
 */
@Component
@RequiredArgsConstructor
public class ShadowComparator {

    static final int MAX_EXAMPLES = 3;
    private static final Gson GSON = new Gson();

    private final ObjectMapper objectMapper;

    public enum Kind {
        /** Same feature, different value for some users. */
        VALUE,
        /** Served by GrowthBook, not by ktoggle (missing, archived or off in the environment). */
        MISSING_IN_KTOGGLE,
        /** Served by ktoggle, not by GrowthBook. */
        MISSING_IN_GROWTHBOOK
    }

    /** One divergent feature, with a few users that show it (simulated attributes, never real ones). */
    public record Divergence(String featureKey, Kind kind, int divergingSamples, List<Example> examples) {
    }

    public record Example(JsonNode attributes, JsonNode growthbook, JsonNode ktoggle) {
    }

    public record Comparison(int samples, int featuresCompared, List<Divergence> divergences) {

        public boolean clean() {
            return divergences.isEmpty();
        }
    }

    /**
     * @param growthbook GrowthBook SDK payload ({@code features}, optionally {@code savedGroups})
     * @param ktoggle    the features ktoggle serves for the same client key
     */
    public Comparison compare(JsonNode growthbook, JsonNode ktoggleFeatures, List<ObjectNode> samples) {
        JsonNode gbFeatures = growthbook.path("features");
        Set<String> keys = new LinkedHashSet<>();
        gbFeatures.fieldNames().forEachRemaining(keys::add);
        ktoggleFeatures.fieldNames().forEachRemaining(keys::add);

        List<Divergence> divergences = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        for (String key : keys) {
            boolean inGb = gbFeatures.has(key);
            boolean inKtoggle = ktoggleFeatures.has(key);
            if (inGb && inKtoggle) {
                shared.add(key);
            } else {
                divergences.add(new Divergence(key, inGb ? Kind.MISSING_IN_KTOGGLE : Kind.MISSING_IN_GROWTHBOOK, samples.size(), List.of()));
            }
        }
        GrowthBook gb = sdk(gbFeatures, growthbook.path("savedGroups"));
        GrowthBook kt = sdk(ktoggleFeatures, NullNode.getInstance());
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, List<Example>> examples = new LinkedHashMap<>();
        for (ObjectNode sample : samples) {
            String attributes = sample.toString();
            gb.setAttributes(attributes);
            kt.setAttributes(attributes);
            for (String key : shared) {
                JsonNode expected = value(gb, key);
                JsonNode actual = value(kt, key);
                if (!sameValue(expected, actual)) {
                    counts.computeIfAbsent(key, k -> new int[1])[0]++;
                    List<Example> list = examples.computeIfAbsent(key, k -> new ArrayList<>());
                    if (list.size() < MAX_EXAMPLES) {
                        list.add(new Example(sample.deepCopy(), expected, actual));
                    }
                }
            }
        }
        counts.forEach((key, count) -> divergences.add(new Divergence(key, Kind.VALUE, count[0], List.copyOf(examples.get(key)))));
        return new Comparison(samples.size(), keys.size(), List.copyOf(divergences));
    }

    private GrowthBook sdk(JsonNode features, JsonNode savedGroups) {
        GBContext.GBContextBuilder context = GBContext.builder().featuresJson(write(features)).attributesJson("{}");
        if (savedGroups != null && savedGroups.isObject() && !savedGroups.isEmpty()) {
            context.savedGroups(GSON.fromJson(write(savedGroups), JsonObject.class));
        }
        return new GrowthBook(context.build());
    }

    private JsonNode value(GrowthBook sdk, String key) {
        Object value = sdk.evalFeature(key, Object.class).getValue();
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(GSON.toJson(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Numbers compare by value (1000 and 1000.0 are the same served value). */
    static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!b.has(name) || !sameValue(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameValue(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
