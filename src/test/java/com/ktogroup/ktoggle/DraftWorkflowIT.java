package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** GrowthBook-style draft workflow: nothing goes live without a publication, protected environments need four eyes. */
@IntegrationTest
class DraftWorkflowIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminApi alice;
    private AdminApi bob;
    private AdminApi carol;
    private AdminApi dave;

    @BeforeEach
    void setUp() throws Exception {
        alice = new AdminApi(mvc, objectMapper, "alice", "ktoggle-editor");
        bob = new AdminApi(mvc, objectMapper, "bob", "ktoggle-approver");
        carol = new AdminApi(mvc, objectMapper, "carol", "ktoggle-admin");
        dave = new AdminApi(mvc, objectMapper, "dave", "ktoggle-viewer");
        if (mvc.perform(get("/admin/v1/attributes/country").with(carol.auth())).andReturn().getResponse().getStatus() == 404) {
            carol.postJson("/admin/v1/attributes", Map.of("key", "country", "datatype", "STRING", "pii", false), 201);
        }
    }

    @Test
    void edits_stay_in_the_draft_until_published() throws Exception {
        Setup s = setup(false);
        String before = etag(s.clientKey());
        JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of("title", "ligar"), 201);
        editEnvironment(alice, draft, s.environment(), true);

        assertThat(etag(s.clientKey())).as("drafts never change what SDKs receive").isEqualTo(before);
        JsonNode view = alice.getJson("/admin/v1/drafts/" + id(draft));
        assertThat(view.path("changes")).singleElement().satisfies(c ->
                assertThat(c.path("section").asText()).isEqualTo("environments." + s.environment()));
        assertThat(view.path("permissions").path("publish").asBoolean()).isTrue();

        JsonNode published = alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 200);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(etag(s.clientKey())).isNotEqualTo(before);
        assertThat(alice.getJson("/admin/v1/features/" + s.feature()).path("revision").asInt())
                .isEqualTo(published.path("publishedRevision").asInt());
        assertThat(alice.getJson("/admin/v1/audit?entityType=FEATURE&entityKey=" + s.feature()).get(0).path("action").asText())
                .isEqualTo("PUBLISH_DRAFT");
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 409);
    }

    @Test
    void protected_environments_require_an_approval_by_someone_else() throws Exception {
        Setup s = setup(true);
        JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        editEnvironment(alice, draft, s.environment(), true);

        assertThat(alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 403).path("messageCode").asText())
                .isEqualTo("REVIEW_REQUIRED");
        bob.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of(), 409);
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/request-review", Map.of("comment", "pode olhar?"), 200);
        assertThat(alice.getJson("/admin/v1/drafts?status=PENDING_REVIEW")).anySatisfy(d -> assertThat(d.path("id").asText()).isEqualTo(id(draft)));
        assertThat(alice.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of(), 403).path("messageCode").asText())
                .isEqualTo("NOT_ALLOWED");
        dave.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of(), 403);
        bob.postJson("/admin/v1/drafts/" + id(draft) + "/request-changes", Map.of(), 400);

        bob.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of("comment", "ok"), 200);
        assertThat(alice.getJson("/admin/v1/drafts/" + id(draft)).path("permissions").path("publish").asBoolean()).isTrue();
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 200);

        JsonNode events = alice.getJson("/admin/v1/drafts/" + id(draft)).path("events");
        assertThat(events).extracting(e -> e.path("type").asText())
                .containsExactly("CREATED", "UPDATED", "REVIEW_REQUESTED", "APPROVED", "PUBLISHED");
    }

    @Test
    void editing_an_approved_draft_requires_a_new_approval() throws Exception {
        Setup s = setup(true);
        JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        editEnvironment(alice, draft, s.environment(), true);
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/request-review", Map.of(), 200);
        bob.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of(), 200);

        JsonNode edited = editEnvironment(alice, alice.getJson("/admin/v1/drafts/" + id(draft)).path("draft"), s.environment(), false);

        assertThat(edited.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(alice.getJson("/admin/v1/drafts/" + id(draft)).path("events"))
                .anySatisfy(e -> assertThat(e.path("type").asText()).isEqualTo("REVIEW_RESET"));
    }

    @Test
    void self_approval_and_reviewer_lists_are_configurable_by_admins() throws Exception {
        JsonNode original = carol.getJson("/admin/v1/settings/review");
        alice.putJson("/admin/v1/settings/review", Map.of("approverRoles", List.of("x"), "version", 0), 403);
        carol.putJson("/admin/v1/settings/review", Map.of("approverRoles", List.of(), "approverUsers", List.of(),
                "version", original.path("version").asLong()), 400);
        JsonNode permissive = carol.putJson("/admin/v1/settings/review", Map.of("approverRoles", List.of("ktoggle-editor"),
                "approverUsers", List.of(), "allowSelfApproval", true, "resetReviewOnChange", true, "bypassEnabled", true,
                "version", original.path("version").asLong()));
        try {
            Setup s = setup(true);
            JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
            editEnvironment(alice, draft, s.environment(), true);
            alice.postJson("/admin/v1/drafts/" + id(draft) + "/request-review", Map.of(), 200);
            alice.postJson("/admin/v1/drafts/" + id(draft) + "/approve", Map.of(), 200);
            alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 200);
        } finally {
            carol.putJson("/admin/v1/settings/review", Map.of("approverRoles", toList(original.path("approverRoles")),
                    "approverUsers", toList(original.path("approverUsers")),
                    "allowSelfApproval", original.path("allowSelfApproval").asBoolean(),
                    "resetReviewOnChange", original.path("resetReviewOnChange").asBoolean(),
                    "bypassEnabled", original.path("bypassEnabled").asBoolean(),
                    "version", permissive.path("version").asLong()));
        }
    }

    @Test
    void admins_can_bypass_the_review_only_with_a_reason_and_it_is_flagged() throws Exception {
        Setup s = setup(true);
        JsonNode draft = carol.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        editEnvironment(carol, draft, s.environment(), true);

        assertThat(alice.getJson("/admin/v1/drafts/" + id(draft)).path("permissions").path("bypass").asBoolean()).isFalse();
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish?bypass=true", Map.of(), "editor tries", 403);
        carol.postJson("/admin/v1/drafts/" + id(draft) + "/publish?bypass=true", Map.of(), 400);
        carol.postJson("/admin/v1/drafts/" + id(draft) + "/publish?bypass=true", Map.of(), "INC-77 checkout fora do ar", 200);

        JsonNode audit = carol.getJson("/admin/v1/audit?entityType=FEATURE&entityKey=" + s.feature()).get(0);
        assertThat(audit.path("action").asText()).isEqualTo("BYPASS_PUBLISH_DRAFT");
        assertThat(audit.path("reason").asText()).isEqualTo("INC-77 checkout fora do ar");
    }

    @Test
    void concurrent_drafts_conflict_and_can_be_rebased() throws Exception {
        Setup s = setup(false);
        JsonNode first = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        JsonNode second = carol.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        editEnvironment(alice, first, s.environment(), true);
        editEnvironment(carol, second, s.environment(), true, Map.of("country", "BR"));
        alice.postJson("/admin/v1/drafts/" + id(first) + "/publish", Map.of(), 200);

        assertThat(carol.postJson("/admin/v1/drafts/" + id(second) + "/publish", Map.of(), 409).path("messageCode").asText())
                .isEqualTo("DRAFT_CONFLICT");
        JsonNode view = carol.getJson("/admin/v1/drafts/" + id(second));
        assertThat(view.path("conflicts")).extracting(JsonNode::asText).containsExactly("environments." + s.environment());

        carol.postJson("/admin/v1/drafts/" + id(second) + "/rebase?keepDraft=true",
                Map.of("version", view.path("draft").path("version").asLong()), 200);
        carol.postJson("/admin/v1/drafts/" + id(second) + "/publish", Map.of(), 200);
        assertThat(carol.getJson("/admin/v1/features/" + s.feature()).path("environments").path(s.environment())
                .path("rules")).hasSize(1);
    }

    @Test
    void housekeeping_rules() throws Exception {
        Setup s = setup(false);
        JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of("title", "noop"), 201);

        assertThat(alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 409).path("messageCode").asText())
                .isEqualTo("NOTHING_TO_PUBLISH");
        dave.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 403);
        new AdminApi(mvc, objectMapper, "eve", "ktoggle-editor").postJson("/admin/v1/drafts/" + id(draft) + "/discard", Map.of(), 403);
        bob.postJson("/admin/v1/drafts/" + id(draft) + "/comments", Map.of("comment", "por que?"), 201);
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/discard", Map.of(), 200);
        editEnvironment(alice, alice.getJson("/admin/v1/drafts/" + id(draft)).path("draft"), s.environment(), true, Map.of(), 409);

        assertThatThrownBy(() -> jdbc.update("UPDATE draft_event SET comment = 'x'")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void revert_creates_a_draft_with_the_old_content() throws Exception {
        Setup s = setup(false);
        JsonNode draft = alice.postJson("/admin/v1/features/" + s.feature() + "/drafts", Map.of(), 201);
        editEnvironment(alice, draft, s.environment(), true);
        alice.postJson("/admin/v1/drafts/" + id(draft) + "/publish", Map.of(), 200);

        JsonNode revert = alice.postJson("/admin/v1/features/" + s.feature() + "/revisions/1/revert", Map.of(), 201);

        assertThat(revert.path("title").asText()).contains("#1");
        assertThat(revert.path("proposed").path("environments").has(s.environment())).isFalse();
        alice.postJson("/admin/v1/drafts/" + id(revert) + "/publish", Map.of(), 200);
        assertThat(alice.getJson("/admin/v1/features/" + s.feature()).path("environments").has(s.environment())).isFalse();
    }

    private Setup setup(boolean requiresReview) throws Exception {
        String environment = unique("env");
        carol.postJson("/admin/v1/environments", Map.of("key", environment, "name", environment, "requiresReview", requiresReview), 201);
        String clientKey = carol.postJson("/admin/v1/sdk-connections", Map.of("name", "it", "environmentKey", environment), 201)
                .path("clientKey").asText();
        String feature = unique("feature");
        alice.postJson("/admin/v1/features", Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false), 201);
        return new Setup(environment, clientKey, feature);
    }

    private JsonNode editEnvironment(AdminApi as, JsonNode draft, String environment, boolean enabled) throws Exception {
        return editEnvironment(as, draft, environment, enabled, Map.of());
    }

    private JsonNode editEnvironment(AdminApi as, JsonNode draft, String environment, boolean enabled,
                                     Map<String, Object> condition) throws Exception {
        return editEnvironment(as, draft, environment, enabled, condition, 200);
    }

    private JsonNode editEnvironment(AdminApi as, JsonNode draft, String environment, boolean enabled,
                                     Map<String, Object> condition, int status) throws Exception {
        List<Object> rules = condition.isEmpty() ? List.of()
                : List.of(Map.of("type", "force", "enabled", true, "condition", condition, "value", true));
        return as.putJson("/admin/v1/drafts/" + id(draft) + "/environments/" + environment,
                Map.of("enabled", enabled, "rules", rules, "version", draft.path("version").asLong()), status);
    }

    private String etag(String clientKey) throws Exception {
        return mvc.perform(get("/api/features/" + clientKey)).andReturn().getResponse().getHeader("ETag");
    }

    private static String id(JsonNode draft) {
        return draft.path("id").asText();
    }

    private static List<String> toList(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private record Setup(String environment, String clientKey, String feature) {
    }
}
