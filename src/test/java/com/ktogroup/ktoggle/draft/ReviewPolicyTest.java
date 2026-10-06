package com.ktogroup.ktoggle.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ktogroup.ktoggle.draft.DraftMerger.MergeResult;
import com.ktogroup.ktoggle.draft.DraftMerger.SectionChange;
import com.ktogroup.ktoggle.environment.Environment;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.ValueType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewPolicyTest {

    private final ReviewPolicy policy = new ReviewPolicy();
    private final FeatureDraft draft = new FeatureDraft(null, "f", null, 1, DraftStatus.PENDING_REVIEW, null, "alice",
            Instant.now(), "alice", Instant.now(), null, 0L);

    @Test
    void environment_changes_affect_only_their_environment() {
        MergeResult merge = merge(Map.of(), new SectionChange("environments.stg", BooleanNode.FALSE, BooleanNode.TRUE));

        assertThat(policy.affectedEnvironments(merge, Map.of("prd", on()))).containsExactly("stg");
    }

    @Test
    void default_value_or_archiving_affects_every_enabled_environment() {
        MergeResult merge = merge(Map.of("dev", on()), new SectionChange("defaultValue", IntNode.valueOf(1), IntNode.valueOf(2)));

        assertThat(policy.affectedEnvironments(merge, Map.of("prd", on(), "stg", new EnvironmentSettings(false, List.of()))))
                .containsExactlyInAnyOrder("prd", "dev");
    }

    @Test
    void prerequisites_affect_every_enabled_environment() {
        MergeResult merge = merge(Map.of(), new SectionChange("prerequisites",
                JsonNodeFactory.instance.arrayNode(),
                JsonNodeFactory.instance.arrayNode().add("x")));

        assertThat(policy.affectedEnvironments(merge, Map.of("prd", on()))).containsExactly("prd");
    }

    @Test
    void only_protected_environments_require_review() {
        assertThat(policy.environmentsRequiringReview(Set.of("dev", "prd", "gone"), List.of(env("dev", false), env("prd", true))))
                .containsExactly("prd");
    }

    @Test
    void approvers_are_matched_by_role_or_username_and_never_approve_their_own_draft_by_default() {
        ReviewSettings byRole = settings(List.of("ktoggle-approver"), List.of(), false);
        ReviewSettings byUser = settings(List.of(), List.of("bob"), false);

        assertThat(policy.canApprove(byRole, draft, List.of(), "bob", Set.of("ktoggle-approver"))).isTrue();
        assertThat(policy.canApprove(byRole, draft, List.of(), "bob", Set.of("ktoggle-editor"))).isFalse();
        assertThat(policy.canApprove(byUser, draft, List.of(), "bob", Set.of())).isTrue();
        assertThat(policy.canApprove(byRole, draft, List.of(), "alice", Set.of("ktoggle-approver"))).as("four-eyes").isFalse();
        assertThat(policy.canApprove(settings(List.of("ktoggle-approver"), List.of(), true), draft, List.of(), "alice",
                Set.of("ktoggle-approver"))).as("self-approval enabled").isTrue();
    }

    @Test
    void whoever_edited_the_draft_cannot_approve_it_either() {
        ReviewSettings byRole = settings(List.of("ktoggle-approver"), List.of(), false);
        List<DraftEvent> events = List.of(event(DraftEvent.Type.CREATED, "alice"), event(DraftEvent.Type.UPDATED, "bob"),
                event(DraftEvent.Type.COMMENTED, "carol"), event(DraftEvent.Type.REVIEW_REQUESTED, "dave"));

        assertThat(policy.canApprove(byRole, draft, events, "bob", Set.of("ktoggle-approver"))).as("edited it").isFalse();
        assertThat(policy.canApprove(byRole, draft, events, "carol", Set.of("ktoggle-approver"))).as("only commented").isTrue();
        assertThat(policy.canApprove(byRole, draft, events, "dave", Set.of("ktoggle-approver"))).as("only asked").isTrue();
        assertThat(policy.canApprove(byRole, draft, List.of(event(DraftEvent.Type.REBASED, "erin")), "erin",
                Set.of("ktoggle-approver"))).as("a rebase may change the content").isFalse();
        assertThat(policy.canApprove(settings(List.of("ktoggle-approver"), List.of(), true), draft, events, "bob",
                Set.of("ktoggle-approver"))).as("self-approval enabled").isTrue();
    }

    @Test
    void moving_the_feature_to_another_project_affects_every_enabled_environment() {
        MergeResult merge = merge(Map.of(), new SectionChange("projectKey", TextNode.valueOf("a"), TextNode.valueOf("b")));

        assertThat(policy.affectedEnvironments(merge, Map.of("prd", on()))).containsExactly("prd");
    }

    @Test
    void bypass_requires_admin_and_the_setting() {
        assertThat(policy.canBypass(settings(List.of("x"), List.of(), false), Set.of("ktoggle-admin"))).isTrue();
        assertThat(policy.canBypass(settings(List.of("x"), List.of(), false), Set.of("ktoggle-editor"))).isFalse();
        ReviewSettings disabled = new ReviewSettings(List.of("x"), List.of(), false, true, false, null, null, 0L);
        assertThat(policy.canBypass(disabled, Set.of("ktoggle-admin"))).isFalse();
    }

    @Test
    void blockers_explain_why_publishing_is_not_possible() {
        MergeResult conflicting = new MergeResult(snapshot(Map.of()), List.of("environments.prd"), List.of());

        assertThat(policy.publishBlockers(draft, conflicting, List.of("prd")))
                .anySatisfy(b -> assertThat(b).contains("Conflicts"))
                .anySatisfy(b -> assertThat(b).contains("No changes"))
                .anySatisfy(b -> assertThat(b).contains("Requires approval"));
        MergeResult clean = merge(Map.of(), new SectionChange("description", null, null));
        assertThat(policy.publishBlockers(draft.withStatus(DraftStatus.APPROVED), clean, List.of("prd"))).isEmpty();
        assertThat(policy.publishBlockers(draft.withStatus(DraftStatus.PUBLISHED), clean, List.of()))
                .singleElement().satisfies(b -> assertThat(b).contains("PUBLISHED"));
    }

    private static MergeResult merge(Map<String, EnvironmentSettings> mergedEnvironments, SectionChange change) {
        return new MergeResult(snapshot(mergedEnvironments), List.of(), List.of(change));
    }

    private static FeatureSnapshot snapshot(Map<String, EnvironmentSettings> environments) {
        return new FeatureSnapshot("f", null, ValueType.NUMBER, IntNode.valueOf(1), null, null, List.of(), false, environments);
    }

    private static EnvironmentSettings on() {
        return new EnvironmentSettings(true, List.of());
    }

    private static Environment env(String key, boolean requiresReview) {
        return new Environment(key, key, null, 0, requiresReview, null, null, 0L);
    }

    private static ReviewSettings settings(List<String> roles, List<String> users, boolean self) {
        return new ReviewSettings(roles, users, self, true, true, null, null, 0L);
    }

    private static DraftEvent event(DraftEvent.Type type, String actor) {
        return new DraftEvent(UUID.randomUUID(), null, type, actor, null, Instant.now());
    }
}
