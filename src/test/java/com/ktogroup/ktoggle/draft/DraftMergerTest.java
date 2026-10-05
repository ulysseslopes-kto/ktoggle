package com.ktogroup.ktoggle.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.ktogroup.ktoggle.draft.DraftMerger.MergeResult;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.ValueType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DraftMergerTest {

    private final DraftMerger merger = new DraftMerger(new ObjectMapper().findAndRegisterModules());

    private static final EnvironmentSettings OFF = new EnvironmentSettings(false, List.of());
    private static final EnvironmentSettings ON = new EnvironmentSettings(true, List.of());
    private static final EnvironmentSettings ON_WITH_RULE = new EnvironmentSettings(true,
            List.of(new ForceRule("fr_1", null, true, null, List.of(), BooleanNode.TRUE)));

    @Test
    void draft_only_change_is_taken_and_reported_as_a_change() {
        FeatureSnapshot base = snapshot(10, Map.of("prd", OFF));
        MergeResult result = merger.merge(base, base, snapshot(10, Map.of("prd", ON)));

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.merged().environments().get("prd").enabled()).isTrue();
        assertThat(result.changes()).singleElement().satisfies(c -> {
            assertThat(c.section()).isEqualTo("environments.prd");
            assertThat(c.environment()).isEqualTo("prd");
        });
    }

    @Test
    void live_only_change_is_kept_and_is_not_a_change_of_the_draft() {
        FeatureSnapshot base = snapshot(10, Map.of("prd", OFF, "stg", OFF));
        FeatureSnapshot live = snapshot(10, Map.of("prd", OFF, "stg", ON));
        FeatureSnapshot draft = snapshot(10, Map.of("prd", ON, "stg", OFF));

        MergeResult result = merger.merge(base, live, draft);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.merged().environments().get("stg").enabled()).as("someone else's change survives").isTrue();
        assertThat(result.merged().environments().get("prd").enabled()).isTrue();
        assertThat(result.changes()).extracting(DraftMerger.SectionChange::section).containsExactly("environments.prd");
    }

    @Test
    void same_change_on_both_sides_is_not_a_conflict() {
        FeatureSnapshot base = snapshot(10, Map.of("prd", OFF));
        FeatureSnapshot both = snapshot(10, Map.of("prd", ON));

        MergeResult result = merger.merge(base, both, both);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void different_changes_to_the_same_section_conflict_and_can_be_resolved_either_way() {
        FeatureSnapshot base = snapshot(10, Map.of("prd", OFF));
        FeatureSnapshot live = snapshot(10, Map.of("prd", ON));
        FeatureSnapshot draft = snapshot(10, Map.of("prd", ON_WITH_RULE));

        MergeResult result = merger.merge(base, live, draft);

        assertThat(result.conflicts()).containsExactly("environments.prd");
        assertThat(merger.resolve(base, live, draft, true).environments().get("prd").rules()).hasSize(1);
        assertThat(merger.resolve(base, live, draft, false).environments().get("prd").rules()).isEmpty();
    }

    @Test
    void metadata_fields_are_independent_sections() {
        FeatureSnapshot base = snapshot(10, Map.of());
        FeatureSnapshot live = withDescription(snapshot(10, Map.of()), "changed live");
        FeatureSnapshot draft = snapshot(20, Map.of());

        MergeResult result = merger.merge(base, live, draft);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.merged().description()).isEqualTo("changed live");
        assertThat(result.merged().defaultValue().asInt()).isEqualTo(20);
        assertThat(result.changes()).extracting(DraftMerger.SectionChange::section).containsExactly("defaultValue");
    }

    @Test
    void environment_added_by_the_draft_is_merged_in() {
        FeatureSnapshot base = snapshot(10, Map.of());
        MergeResult result = merger.merge(base, base, snapshot(10, Map.of("dev", ON)));

        assertThat(result.merged().environments()).containsKey("dev");
        assertThat(result.changes()).extracting(DraftMerger.SectionChange::section).containsExactly("environments.dev");
    }

    private static FeatureSnapshot snapshot(int defaultValue, Map<String, EnvironmentSettings> environments) {
        return new FeatureSnapshot("limit", null, ValueType.NUMBER, IntNode.valueOf(defaultValue), "desc", null, List.of(),
                false, new HashMap<>(environments));
    }

    private static FeatureSnapshot withDescription(FeatureSnapshot s, String description) {
        return new FeatureSnapshot(s.key(), s.projectKey(), s.valueType(), s.defaultValue(), description, s.owner(), s.tags(),
                s.archived(), s.environments());
    }
}
