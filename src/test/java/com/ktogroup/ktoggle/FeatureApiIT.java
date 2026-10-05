package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class FeatureApiIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private AdminApi admin;
    private Fixtures fixtures;

    @BeforeEach
    void setUp() {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        fixtures = new Fixtures(admin);
    }

    @Test
    void feature_list_can_be_filtered_by_project_tag_search_and_archived_state() throws Exception {
        String project = fixtures.project();
        String tag = unique("tag");
        String needle = unique("needle");
        String tagged = unique("feature");
        String plain = unique("feature");
        String archived = unique("feature");
        admin.postJson("/admin/v1/features", Map.of("key", tagged, "projectKey", project, "valueType", "BOOLEAN", "defaultValue", false,
                "description", "about " + needle, "tags", List.of(tag, "other")), 201);
        admin.postJson("/admin/v1/features", Map.of("key", plain, "projectKey", project, "valueType", "STRING", "defaultValue", "a"), 201);
        admin.postJson("/admin/v1/features", Map.of("key", archived, "projectKey", project, "valueType", "NUMBER", "defaultValue", 1), 201);
        fixtures.publishMetadata(archived, Map.of("archived", true));

        assertThat(keys(admin.getJson("/admin/v1/features?projectKey=" + project))).containsExactlyInAnyOrder(tagged, plain);
        assertThat(keys(admin.getJson("/admin/v1/features?projectKey=" + project + "&archived=true"))).containsExactly(archived);
        assertThat(keys(admin.getJson("/admin/v1/features?projectKey=" + project + "&tag=" + tag))).containsExactly(tagged);
        assertThat(keys(admin.getJson("/admin/v1/features?search=" + needle.toUpperCase()))).containsExactly(tagged);
        assertThat(keys(admin.getJson("/admin/v1/features?search=" + plain))).containsExactly(plain);
        assertThat(keys(admin.getJson("/admin/v1/features?projectKey=" + project + "&tag=nope"))).isEmpty();
    }

    @Test
    void feature_creation_is_validated_and_metadata_changes_go_through_a_draft() throws Exception {
        String key = fixtures.feature("BOOLEAN", false);
        assertThat(admin.postJson("/admin/v1/features", Map.of("key", key, "valueType", "BOOLEAN", "defaultValue", false), 409)
                .path("messageCode").asText()).isEqualTo("ENTITY_ALREADY_EXISTS");
        admin.postJson("/admin/v1/features", Map.of("key", "bad key", "valueType", "BOOLEAN", "defaultValue", false), 400);
        admin.postJson("/admin/v1/features", Map.of("key", unique("f"), "defaultValue", false), 400);
        assertThat(admin.postJson("/admin/v1/features", Map.of("key", unique("f"), "valueType", "BOOLEAN", "defaultValue", "yes"), 400)
                .path("messageCode").asText()).isEqualTo("INVALID_VALUE");
        admin.postJson("/admin/v1/features", Map.of("key", unique("f"), "valueType", "NUMBER", "defaultValue", "1"), 400);
        admin.postJson("/admin/v1/features", Map.of("key", unique("f"), "valueType", "BOOLEAN", "defaultValue", false,
                "projectKey", unique("missing")), 404);
        admin.getJson("/admin/v1/features/" + unique("missing"), 404);

        JsonNode feature = admin.getJson("/admin/v1/features/" + key);
        assertThat(feature.path("revision").asInt()).isEqualTo(1);
        JsonNode draft = fixtures.draft(key);
        String path = "/admin/v1/drafts/" + draft.path("id").asText() + "/metadata";
        long version = draft.path("version").asLong();
        JsonNode edited = admin.putJson(path, Map.of("defaultValue", true, "description", "new", "owner", "payments",
                "tags", List.of("a", "b"), "version", version));
        assertThat(edited.path("proposed").path("defaultValue").asBoolean()).isTrue();
        assertThat(admin.getJson("/admin/v1/features/" + key).path("defaultValue").asBoolean()).as("live is untouched").isFalse();

        admin.putJson(path, Map.of("defaultValue", true, "version", version), 409);
        long next = edited.path("version").asLong();
        assertThat(admin.putJson(path, Map.of("defaultValue", "text", "version", next), 400).path("messageCode").asText())
                .isEqualTo("INVALID_VALUE");
        admin.putJson(path, Map.of("defaultValue", true, "projectKey", unique("missing"), "version", next), 404);

        fixtures.publish(edited);
        JsonNode updated = admin.getJson("/admin/v1/features/" + key);
        assertThat(updated.path("defaultValue").asBoolean()).isTrue();
        assertThat(updated.path("owner").asText()).isEqualTo("payments");
        assertThat(updated.path("tags")).hasSize(2);
        assertThat(updated.path("revision").asInt()).isEqualTo(2);
        assertThat(updated.path("updatedBy").asText()).isEqualTo("alice");

        JsonNode cleared = fixtures.publishMetadata(key, Map.of("defaultValue", false, "tags", List.of()));
        assertThat(cleared.path("tags")).isEmpty();
        assertThat(cleared.path("revision").asInt()).isEqualTo(3);
    }

    @Test
    void rollout_rules_get_generated_ids_and_are_published_with_their_coverage() throws Exception {
        String hashAttribute = fixtures.attribute("userId", "STRING", true, true);
        String environment = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);

        JsonNode saved = fixtures.publishEnvironment(feature, environment, true, List.of(
                Map.of("type", "rollout", "enabled", true, "value", true, "coverage", 0.25, "hashAttribute", hashAttribute),
                Map.of("type", "force", "id", "fr_custom", "enabled", false, "value", false, "description", "off")));

        JsonNode rules = saved.path("environments").path(environment).path("rules");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).path("id").asText()).matches("^fr_[a-z0-9]{12}$");
        assertThat(rules.get(0).path("type").asText()).isEqualTo("rollout");
        assertThat(rules.get(0).path("coverage").asDouble()).isEqualTo(0.25);
        assertThat(rules.get(1).path("id").asText()).isEqualTo("fr_custom");
    }

    @Test
    void invalid_rules_are_rejected_with_a_specific_message() throws Exception {
        String stringAttribute = fixtures.attribute("userId", "STRING", true, true);
        String booleanAttribute = fixtures.attribute("vip", "BOOLEAN", false, false);
        String environment = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);

        JsonNode draft = fixtures.draft(feature);

        assertThat(rulesError(draft, environment, rollout(1.5, stringAttribute))).contains("coverage must be between 0 and 1");
        assertThat(rulesError(draft, environment, rollout(-0.1, stringAttribute))).contains("coverage must be between 0 and 1");
        assertThat(rulesError(draft, environment, rollout(0.5, unique("unknown")))).contains("unknown hashAttribute");
        assertThat(rulesError(draft, environment, rollout(0.5, booleanAttribute))).contains("must be a STRING or NUMBER attribute");
        assertThat(rulesError(draft, environment, Map.of("type", "force", "enabled", true, "value", "not a boolean")))
                .contains("rules[0]: value is not a valid BOOLEAN");
        assertThat(rulesError(draft, environment, Map.of("type", "force", "enabled", true, "value", true,
                "savedGroups", List.of(unique("missing"))))).contains("unknown saved group");
        assertThat(rulesError(draft, environment, Map.of("type", "force", "id", "fr_same", "enabled", true, "value", true),
                Map.of("type", "force", "id", "fr_same", "enabled", true, "value", false))).contains("Duplicated rule id 'fr_same'");

        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(Map.of("type", "force", "enabled", true, "value", true));
        }
        assertThat(rulesError(draft, environment, tooMany.toArray())).contains("at most 100 rules");

        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(rulesError(draft, environment, withNull.toArray())).contains("rules[0] is null");

        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("version", draft.path("version").asLong());
        admin.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/environments/" + unique("missing"), body, 404);
        body.put("version", 999);
        admin.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/environments/" + environment, body, 409);
    }

    @Test
    void toggle_archive_and_revision_history_with_revert() throws Exception {
        String environment = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(fixtures.forceRule(true, Map.of())));

        JsonNode toggle = fixtures.editEnvironment(fixtures.draft(feature), environment, false,
                List.of(fixtures.forceRule(true, Map.of())));
        JsonNode toggled = admin.postJson("/admin/v1/drafts/" + toggle.path("id").asText() + "/publish", Map.of(),
                "maintenance window", 200);
        assertThat(toggled.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(admin.getJson("/admin/v1/features/" + feature).path("environments").path(environment).path("enabled").asBoolean())
                .isFalse();
        JsonNode scratch = fixtures.draft(feature);
        admin.putJson("/admin/v1/drafts/" + scratch.path("id").asText() + "/environments/" + unique("missing"),
                Map.of("enabled", true, "version", scratch.path("version").asLong()), 404);

        assertThat(fixtures.publishMetadata(feature, Map.of("archived", true)).path("archived").asBoolean()).isTrue();
        assertThat(fixtures.publishMetadata(feature, Map.of("archived", false)).path("archived").asBoolean()).isFalse();

        JsonNode revisions = admin.getJson("/admin/v1/features/" + feature + "/revisions");
        assertThat(revisions).hasSize(5);
        assertThat(revisions.get(0).path("revision").asInt()).isEqualTo(5);
        assertThat(revisions.get(2).path("comment").asText()).isEqualTo("maintenance window");
        admin.getJson("/admin/v1/features/" + unique("missing") + "/revisions", 404);

        JsonNode revisionTwo = admin.getJson("/admin/v1/features/" + feature + "/revisions/2");
        assertThat(revisionTwo.path("snapshot").path("environments").path(environment).path("enabled").asBoolean()).isTrue();
        assertThat(revisionTwo.path("createdBy").asText()).isEqualTo("alice");
        assertThat(admin.getJson("/admin/v1/features/" + feature + "/revisions/99", 404).path("message").asText())
                .contains("Revision 99");

        JsonNode revert = fixtures.revert(feature, 2);
        admin.postJson("/admin/v1/drafts/" + revert.path("id").asText() + "/publish", Map.of(), "undo maintenance", 200);
        JsonNode restored = admin.getJson("/admin/v1/features/" + feature);
        assertThat(restored.path("revision").asInt()).isEqualTo(6);
        assertThat(restored.path("environments").path(environment).path("enabled").asBoolean()).isTrue();
        assertThat(restored.path("environments").path(environment).path("rules")).hasSize(1);
        admin.postJson("/admin/v1/features/" + feature + "/revisions/99/revert", Map.of(), 404);
        assertThat(admin.getJson("/admin/v1/audit?entityType=FEATURE&entityKey=" + feature).get(0).path("action").asText())
                .isEqualTo("PUBLISH_DRAFT");
    }

    @Test
    void simulation_uses_the_saved_configuration_or_an_unsaved_proposal() throws Exception {
        String attribute = fixtures.attribute("country", "STRING", false, false);
        String environment = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(fixtures.forceRule(true, Map.of(attribute, "BR"))));

        JsonNode matching = admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature, "environmentKey", environment,
                "attributes", Map.of(attribute, "BR")), 200);
        assertThat(matching.path("value").asBoolean()).isTrue();
        assertThat(matching.path("source").asText()).isEqualTo("force");
        assertThat(matching.path("trace").get(0).path("conditionMatched").asBoolean()).isTrue();
        assertThat(matching.path("trace").get(0).path("selected").asBoolean()).isTrue();

        JsonNode other = admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature, "environmentKey", environment,
                "attributes", Map.of(attribute, "PT")), 200);
        assertThat(other.path("value").asBoolean()).isFalse();
        assertThat(other.path("trace").get(0).path("conditionMatched").asBoolean()).isFalse();
        assertThat(other.path("ruleId").isNull()).isTrue();

        JsonNode proposed = admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature, "environmentKey", environment,
                "attributes", Map.of(attribute, "PT"),
                "proposed", Map.of("enabled", true, "rules", List.of(fixtures.forceRule(true, Map.of(attribute, "PT"))))), 200);
        assertThat(proposed.path("value").asBoolean()).isTrue();
        assertThat(proposed.path("ruleId").asText()).startsWith("fr_");

        JsonNode withoutAttributes = admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature,
                "environmentKey", environment), 200);
        assertThat(withoutAttributes.path("value").asBoolean()).isFalse();

        JsonNode invalidProposal = admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature, "environmentKey", environment,
                "proposed", Map.of("enabled", true, "rules", List.of(Map.of("type", "force", "enabled", true, "value", "x")))), 400);
        assertThat(invalidProposal.path("messageCode").asText()).isEqualTo("INVALID_VALUE");

        admin.postJson("/admin/v1/simulate", Map.of("featureKey", feature, "environmentKey", unique("missing")), 404);
        admin.postJson("/admin/v1/simulate", Map.of("featureKey", unique("missing"), "environmentKey", environment), 404);
        admin.postJson("/admin/v1/simulate", Map.of("featureKey", " ", "environmentKey", environment), 400);
    }

    private String rulesError(JsonNode draft, String environment, Object... rules) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("enabled", true, "version", draft.path("version").asLong(),
                "rules", java.util.Arrays.asList(rules)));
        String response = admin.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/admin/v1/drafts/" + draft.path("id").asText() + "/environments/" + environment)
                        .contentType(MediaType.APPLICATION_JSON).content(body), 400)
                .getResponse().getContentAsString();
        return objectMapper.readTree(response).path("message").asText();
    }

    private static Map<String, Object> rollout(double coverage, String hashAttribute) {
        return Map.of("type", "rollout", "enabled", true, "value", true, "coverage", coverage, "hashAttribute", hashAttribute);
    }

    private static List<String> keys(JsonNode features) {
        List<String> keys = new ArrayList<>();
        features.forEach(f -> keys.add(f.path("key").asText()));
        return keys;
    }
}
