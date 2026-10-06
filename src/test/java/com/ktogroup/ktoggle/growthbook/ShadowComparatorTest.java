package com.ktogroup.ktoggle.growthbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.growthbook.ShadowComparator.Divergence;
import com.ktogroup.ktoggle.growthbook.ShadowComparator.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShadowComparatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShadowComparator comparator = new ShadowComparator(objectMapper);

    @Test
    void saved_group_references_and_inlined_groups_are_the_same_behaviour() throws Exception {
        JsonNode growthbook = json("""
                {"features": {"vip-lobby": {"defaultValue": false, "rules": [
                  {"condition": {"id": {"$inGroup": "grp_vip"}}, "force": true}]}},
                 "savedGroups": {"grp_vip": ["u1", "u2"]}}""");
        JsonNode ktoggle = json("""
                {"vip-lobby": {"defaultValue": false, "rules": [{"condition": {"id": {"$in": ["u1", "u2"]}}, "force": true}]}}""");

        ShadowComparator.Comparison result = comparator.compare(growthbook, ktoggle, samples(growthbook, 400));

        assertThat(result.clean()).isTrue();
        assertThat(samples(growthbook, 400)).as("group members are sampled").anyMatch(s -> s.path("id").asText().equals("u1"));
    }

    @Test
    void reports_value_divergences_with_examples_and_missing_features() throws Exception {
        JsonNode growthbook = json("""
                {"features": {
                  "limit": {"defaultValue": 1000, "rules": [{"condition": {"vipLevel": {"$gte": 3}}, "force": 50000}]},
                  "only-gb": {"defaultValue": true}}}""");
        JsonNode ktoggle = json("""
                {"limit": {"defaultValue": 1000.0, "rules": [{"condition": {"vipLevel": {"$gt": 3}}, "force": 50000}]},
                 "only-ktoggle": {"defaultValue": "x"}}""");

        ShadowComparator.Comparison result = comparator.compare(growthbook, ktoggle, samples(growthbook, 500));

        Map<String, Divergence> byKey = new java.util.HashMap<>();
        result.divergences().forEach(d -> byKey.put(d.featureKey(), d));
        assertThat(byKey.get("only-gb").kind()).isEqualTo(Kind.MISSING_IN_KTOGGLE);
        assertThat(byKey.get("only-ktoggle").kind()).isEqualTo(Kind.MISSING_IN_GROWTHBOOK);
        Divergence limit = byKey.get("limit");
        assertThat(limit.kind()).as("$gte vs $gt: users exactly at 3 differ").isEqualTo(Kind.VALUE);
        assertThat(limit.examples()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.attributes().path("vipLevel").asDouble()).isEqualTo(3.0);
            assertThat(e.growthbook().asInt()).isEqualTo(50000);
            assertThat(e.ktoggle().asInt()).isEqualTo(1000);
        });
        assertThat(limit.examples()).hasSizeLessThanOrEqualTo(ShadowComparator.MAX_EXAMPLES);
    }

    @Test
    void numbers_compare_by_value() {
        assertThat(ShadowComparator.sameValue(objectMapper.valueToTree(1000), objectMapper.valueToTree(1000.0))).isTrue();
        assertThat(ShadowComparator.sameValue(objectMapper.valueToTree(Map.of("a", 1)), objectMapper.valueToTree(Map.of("a", 1.0))))
                .isTrue();
        assertThat(ShadowComparator.sameValue(objectMapper.valueToTree("1"), objectMapper.valueToTree(1))).isFalse();
    }

    private List<ObjectNode> samples(JsonNode payload, int count) {
        return AttributeSampler.samples(List.of(), List.of(payload), count, 3L);
    }

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }
}
