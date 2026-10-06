package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.bundle.BundleActivatedEvent;
import com.ktogroup.ktoggle.bundle.RuleScheduleWatcher;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import com.ktogroup.ktoggle.decision.DecisionIngestService;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.GBContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class KtoggleFlowIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ActiveBundleRegistry registry;
    @Autowired
    private DecisionIngestService decisionIngestService;
    @Autowired
    private CanonicalJson canonicalJson;
    @Autowired
    private RuleScheduleWatcher scheduleWatcher;

    private AdminApi admin;
    private AdminApi viewer;

    @BeforeEach
    void setUp() throws Exception {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        viewer = new AdminApi(mvc, objectMapper, "victor", "ktoggle-viewer");
        createAttributeIfAbsent("country", "STRING", false, false);
        createAttributeIfAbsent("userId", "STRING", true, true);
    }

    @Test
    void publishes_signed_bundles_serves_them_to_the_sdk_and_replays_past_decisions() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));
        Served v1 = fetch(s.clientKey());

        assertThat(sdkIsOn(v1.body(), s.feature(), "{\"country\":\"BR\"}")).isTrue();
        assertThat(sdkIsOn(v1.body(), s.feature(), "{\"country\":\"AR\"}")).isFalse();
        assertThat(v1.body().path("bundleHash").asText()).isEqualTo(v1.hash());
        assertThat(mvc.perform(get("/api/features/" + s.clientKey())).andReturn().getResponse().getHeader("x-sse-support"))
                .as("GrowthBook JS SDKs only stream when this header is present").isEqualTo("enabled");

        MvcResult notModified = mvc.perform(get("/api/features/" + s.clientKey()).header("If-None-Match", "\"" + v1.hash() + "\""))
                .andReturn();
        assertThat(notModified.getResponse().getStatus()).isEqualTo(304);

        Instant between = Instant.now();
        rules(s, Map.of("country", "PT"));
        Served v2 = fetch(s.clientKey());
        assertThat(v2.hash()).isNotEqualTo(v1.hash());
        assertThat(sdkIsOn(v2.body(), s.feature(), "{\"country\":\"BR\"}")).isFalse();

        // Version independence: the old decision is reproduced from the old immutable bundle.
        JsonNode replayOld = admin.postJson("/admin/v1/replay",
                Map.of("bundleHash", v1.hash(), "featureKey", s.feature(), "attributes", Map.of("country", "BR")), 200);
        assertThat(replayOld.path("result").path("value").asBoolean()).isTrue();
        assertThat(replayOld.path("result").path("ruleId").asText()).startsWith("fr_");
        assertThat(replayOld.path("featureRevision").asInt()).isPositive();

        JsonNode replayAt = admin.postJson("/admin/v1/replay/at", Map.of("clientKey", s.clientKey(), "instant", between.toString(),
                "featureKey", s.feature(), "attributes", Map.of("country", "BR")), 200);
        assertThat(replayAt.path("bundleHash").asText()).isEqualTo(v1.hash());

        assertThat(admin.getJson("/admin/v1/audit/verify").path("valid").asBoolean()).isTrue();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations/verify").path("valid").asBoolean()).isTrue();
        JsonNode audit = admin.getJson("/admin/v1/audit?entityType=FEATURE&entityKey=" + s.feature());
        assertThat(audit).allSatisfy(entry -> assertThat(entry.path("actor").asText()).isEqualTo("alice"));
        assertThat(admin.getJson("/admin/v1/features/" + s.feature() + "/revisions")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void rollback_pins_a_previous_bundle_until_unpinned() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));
        String v1 = fetch(s.clientKey()).hash();
        rules(s, Map.of("country", "PT"));

        admin.postJson("/admin/v1/sdk-connections/" + s.clientKey() + "/bundles/" + v1 + "/activate", Map.of(), 400);
        JsonNode activation = admin.postJson("/admin/v1/sdk-connections/" + s.clientKey() + "/bundles/" + v1 + "/activate",
                Map.of(), "incident INC-42", 200);
        assertThat(activation.path("kind").asText()).isEqualTo("ROLLBACK");
        assertThat(fetch(s.clientKey()).hash()).isEqualTo(v1);

        rules(s, Map.of("country", "AR"));
        assertThat(fetch(s.clientKey()).hash()).as("pinned connections are not republished").isEqualTo(v1);

        admin.postJson("/admin/v1/sdk-connections/" + s.clientKey() + "/unpin", Map.of(), 200);
        Served latest = fetch(s.clientKey());
        assertThat(latest.hash()).isNotEqualTo(v1);
        assertThat(sdkIsOn(latest.body(), s.feature(), "{\"country\":\"AR\"}")).isTrue();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations/verify").path("valid").asBoolean()).isTrue();
    }

    @Test
    void a_published_change_that_does_not_alter_the_payload_does_not_create_a_new_bundle() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));
        String before = fetch(s.clientKey()).hash();
        int activations = admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations").size();

        JsonNode published = new Fixtures(admin).publishMetadata(s.feature(), Map.of("description", "only metadata"));

        assertThat(published.path("description").asText()).isEqualTo("only metadata");
        assertThat(fetch(s.clientKey()).hash()).isEqualTo(before);
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations")).hasSize(activations);
    }

    @Test
    void append_only_tables_reject_updates_and_deletes() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'mallory'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bundle SET created_by = 'mallory'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bundle_activation")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE feature_revision SET comment = 'x'")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void forged_bundle_is_never_served_and_breaks_chain_verification() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));
        String legit = fetch(s.clientKey()).hash();

        String forgedContent = canonicalJson.canonicalize(Map.of("contractVersion", "ktoggle.bundle.v1",
                "target", Map.of("clientKey", s.clientKey(), "environment", s.environment(), "projects", List.of()),
                "evaluator", Map.of("spec", "growthbook-features", "hashVersion", 1, "referenceEvaluator", "growthbook-sdk-java@0.10.5"),
                "sources", Map.of(), "payload", Map.of("features", Map.of(s.feature(), Map.of("defaultValue", true)))));
        String forgedHash = Hashes.sha256Hex(forgedContent);
        jdbc.update("""
                INSERT INTO bundle (hash, contract_version, client_key, environment_key, content, payload_hash, signature_alg,
                                    key_id, signature, created_at, created_by)
                VALUES (?, 'ktoggle.bundle.v1', ?, ?, ?, ?, 'ECDSA_P256_SHA256', 'local-dev', 'bm90LWEtc2lnbmF0dXJl', now(), 'mallory')""",
                forgedHash, s.clientKey(), s.environment(), forgedContent, forgedHash);
        long position = jdbc.queryForObject("SELECT max(position) FROM bundle_activation WHERE client_key = ?", Long.class, s.clientKey()) + 1;
        jdbc.update("""
                INSERT INTO bundle_activation (id, client_key, position, bundle_hash, kind, change_id, activated_by, activated_at,
                                               reason, prev_hash, hash)
                VALUES (?, ?, ?, ?, 'PUBLISH', ?, 'mallory', ?, NULL, NULL, ?)""",
                UUID.randomUUID(), s.clientKey(), position, forgedHash, UUID.randomUUID(), Timestamp.from(Instant.now()),
                "f".repeat(64));

        registry.onBundleActivated(new BundleActivatedEvent(s.clientKey(), forgedHash));

        assertThat(fetch(s.clientKey()).hash()).isEqualTo(legit);
        JsonNode verification = admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations/verify");
        assertThat(verification.path("valid").asBoolean()).isFalse();
        assertThat(verification.path("brokenAt").asLong()).isEqualTo(position);
    }

    @Test
    void decision_events_keep_only_non_pii_attributes_and_can_be_verified() throws Exception {
        Setup s = setup();
        rules(s, Map.of("country", "BR"));
        String hash = fetch(s.clientKey()).hash();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> attributes = Map.of("country", "BR", "userId", "123.456.789-00");

        MvcResult ingest = mvc.perform(post("/api/decisions/" + s.clientKey()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("events", List.of(Map.of("eventId", eventId, "bundleHash", hash,
                        "featureKey", s.feature(), "value", true, "ruleId", "fr_x", "occurredAt", Instant.now().toString(),
                        "attributes", attributes)))))).andReturn();
        assertThat(ingest.getResponse().getStatus()).isEqualTo(202);
        assertThat(admin.json(ingest.getResponse().getContentAsString()).path("accepted").asInt()).isEqualTo(1);
        decisionIngestService.flush();

        JsonNode stored = admin.getJson("/admin/v1/decisions/" + eventId);
        assertThat(stored.path("attributes").has("country")).isTrue();
        assertThat(stored.path("attributes").has("userId")).as("PII is never stored in clear").isFalse();
        assertThat(admin.postJson("/admin/v1/decisions/" + eventId + "/verify-attributes", attributes, 200).path("matches").asBoolean()).isTrue();
        assertThat(admin.postJson("/admin/v1/decisions/" + eventId + "/verify-attributes",
                Map.of("country", "BR", "userId", "999"), 200).path("matches").asBoolean()).isFalse();
    }

    @Test
    void roles_are_enforced_and_sdk_endpoints_are_public() throws Exception {
        assertThat(mvc.perform(post("/admin/v1/environments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        viewer.postJson("/admin/v1/features", Map.of("key", unique("f"), "valueType", "BOOLEAN", "defaultValue", false), 403);
        new AdminApi(mvc, objectMapper, "eddie", "ktoggle-editor")
                .postJson("/admin/v1/environments", Map.of("key", unique("env"), "name", "x"), 403);
        assertThat(viewer.getJson("/admin/v1/features")).isNotNull();
        assertThat(mvc.perform(get("/api/features/sdk-doesnotexist")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void experiments_are_served_in_growthbook_format_and_replay_reports_the_assignment() throws Exception {
        Setup s = setup();
        Map<String, Object> experiment = Map.of("type", "experiment", "enabled", true, "description", "Checkout copy",
                "trackingKey", "exp-" + s.feature(), "hashAttribute", "userId", "coverage", 1.0,
                "variations", List.of(Map.of("key", "0", "name", "Control", "value", false, "weight", 0.5),
                        Map.of("key", "1", "name", "Treatment", "value", true, "weight", 0.5)));
        new Fixtures(admin).publishEnvironment(s.feature(), s.environment(), true, List.of(experiment));

        JsonNode rule = fetch(s.clientKey()).body().path("features").path(s.feature()).path("rules").get(0);
        assertThat(rule.path("key").asText()).isEqualTo("exp-" + s.feature());
        assertThat(rule.path("variations")).hasSize(2);
        assertThat(rule.path("weights").get(1).asDouble()).isEqualTo(0.5);
        assertThat(rule.path("meta").get(1).path("key").asText()).isEqualTo("1");
        assertThat(rule.path("hashVersion").asInt()).isEqualTo(2);
        assertThat(rule.has("force")).as("experiments must not be compiled as forced values").isFalse();

        JsonNode replay = admin.postJson("/admin/v1/replay", Map.of("bundleHash", fetch(s.clientKey()).hash(),
                "featureKey", s.feature(), "attributes", Map.of("userId", "user-42")), 200);
        JsonNode assignment = replay.path("result").path("experiment");
        assertThat(assignment.path("trackingKey").asText()).isEqualTo("exp-" + s.feature());
        assertThat(assignment.path("inExperiment").asBoolean()).isTrue();
        assertThat(replay.path("result").path("value").asBoolean())
                .isEqualTo(assignment.path("variationIndex").asInt() == 1);

        // The admin API returns the rule with server-computed fields; sending it back unchanged (as the UI does for
        // untouched rules) must be accepted.
        JsonNode stored = admin.getJson("/admin/v1/features/" + s.feature()).path("environments").path(s.environment()).path("rules");
        assertThat(stored.get(0).path("type").asText()).isEqualTo("experiment");
        new Fixtures(admin).publishEnvironment(s.feature(), s.environment(), false,
                List.of(objectMapper.convertValue(stored.get(0), Map.class)));
    }

    @Test
    void scheduled_rules_go_live_when_their_window_opens_and_the_scheduler_is_audited() throws Exception {
        Setup s = setup();
        Instant startsAt = Instant.now().plusMillis(1500);
        new Fixtures(admin).publishEnvironment(s.feature(), s.environment(), true, List.of(Map.of("type", "force",
                "enabled", true, "value", true, "schedule", Map.of("startsAt", startsAt.toString()))));

        JsonNode stored = admin.getJson("/admin/v1/features/" + s.feature()).path("environments").path(s.environment())
                .path("rules").get(0).path("schedule");
        assertThat(Instant.parse(stored.path("startsAt").asText())).isEqualTo(startsAt);
        assertThat(stored.has("empty")).as("helpers are not serialized").isFalse();
        assertThat(sdkIsOn(fetch(s.clientKey()).body(), s.feature(), "{}")).as("not started yet").isFalse();

        JsonNode future = admin.postJson("/admin/v1/simulate", Map.of("featureKey", s.feature(), "environmentKey", s.environment(),
                "at", startsAt.plusSeconds(60).toString()), 200);
        assertThat(future.path("value").asBoolean()).as("simulation at a future instant").isTrue();

        Thread.sleep(Duration.between(Instant.now(), startsAt).plusMillis(200).toMillis());
        scheduleWatcher.publishDueSchedules();

        assertThat(sdkIsOn(fetch(s.clientKey()).body(), s.feature(), "{}")).isTrue();
        JsonNode last = admin.getJson("/admin/v1/sdk-connections/" + s.clientKey() + "/activations").get(0);
        assertThat(last.path("activatedBy").asText()).isEqualTo("system:scheduler");
        assertThat(last.path("reason").asText()).contains(s.feature() + "/" + s.environment()).contains("started");
    }

    @Test
    void prerequisites_are_edited_in_drafts_gate_the_child_and_reject_cycles() throws Exception {
        Setup s = setup();
        Fixtures fixtures = new Fixtures(admin);
        String parent = unique("parent");
        admin.postJson("/admin/v1/features", Map.of("key", parent, "valueType", "BOOLEAN", "defaultValue", false), 201);
        fixtures.publishEnvironment(parent, s.environment(), true,
                List.of(Map.of("type", "force", "enabled", true, "condition", Map.of("country", "BR"), "value", true)));
        fixtures.publishEnvironment(s.feature(), s.environment(), true,
                List.of(Map.of("type", "force", "enabled", true, "value", true)));

        JsonNode draft = fixtures.draft(s.feature());
        JsonNode updated = admin.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/prerequisites",
                Map.of("version", draft.path("version").asLong(),
                        "prerequisites", List.of(Map.of("featureKey", parent, "condition", Map.of("value", true)))), 200);
        fixtures.publish(updated);

        JsonNode body = fetch(s.clientKey()).body();
        assertThat(sdkIsOn(body, s.feature(), "{\"country\":\"BR\"}")).isTrue();
        assertThat(sdkIsOn(body, s.feature(), "{\"country\":\"AR\"}")).as("gated by the parent").isFalse();
        JsonNode simulated = admin.postJson("/admin/v1/simulate", Map.of("featureKey", s.feature(), "environmentKey", s.environment(),
                "attributes", Map.of("country", "AR")), 200);
        assertThat(simulated.path("source").asText()).isEqualTo("prerequisite");

        JsonNode dependents = admin.getJson("/admin/v1/features/" + parent + "/dependents");
        assertThat(dependents).singleElement().satisfies(d -> {
            assertThat(d.path("featureKey").asText()).isEqualTo(s.feature());
            assertThat(d.path("featureLevel").asBoolean()).isTrue();
        });

        JsonNode parentDraft = fixtures.draft(parent);
        JsonNode cycle = admin.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/admin/v1/drafts/" + parentDraft.path("id").asText() + "/prerequisites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", parentDraft.path("version").asLong(),
                                "prerequisites", List.of(Map.of("featureKey", s.feature(), "condition", Map.of("value", true)))))), 400)
                .getResponse().getContentAsString().transform(this::readTree);
        assertThat(cycle.path("message").asText()).contains("Circular prerequisite");

        JsonNode archiving = fixtures.editMetadata(fixtures.draft(parent), Map.of("archived", true));
        JsonNode blocked = admin.postJson("/admin/v1/drafts/" + archiving.path("id").asText() + "/publish", Map.of(), 400);
        assertThat(blocked.path("message").asText()).as("the child would silently turn off").contains(s.feature());
        assertThat(admin.getJson("/admin/v1/features/" + parent).path("archived").asBoolean()).isFalse();
    }

    @Test
    void invalid_rules_are_rejected_with_details() throws Exception {
        Setup s = setup();
        JsonNode draft = new Fixtures(admin).draft(s.feature());
        JsonNode error = admin.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/admin/v1/drafts/" + draft.path("id").asText() + "/environments/" + s.environment())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "version", draft.path("version").asLong(),
                                "rules", List.of(Map.of("type", "force", "enabled", true, "condition", Map.of("plan", "gold"),
                                        "value", true))))), 400)
                .getResponse().getContentAsString().transform(this::readTree);
        assertThat(error.path("messageCode").asText()).isEqualTo("INVALID_CONDITION");
        assertThat(error.path("data").get(0).asText()).contains("unknown attribute 'plan'");
    }

    private Setup setup() throws Exception {
        String environment = unique("env");
        admin.postJson("/admin/v1/environments", Map.of("key", environment, "name", environment), 201);
        String clientKey = admin.postJson("/admin/v1/sdk-connections", Map.of("name", "it", "environmentKey", environment), 201)
                .path("clientKey").asText();
        String feature = unique("feature");
        admin.postJson("/admin/v1/features", Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false), 201);
        return new Setup(environment, clientKey, feature);
    }

    private void rules(Setup s, Map<String, Object> condition) throws Exception {
        new Fixtures(admin).publishEnvironment(s.feature(), s.environment(), true,
                List.of(Map.of("type", "force", "enabled", true, "condition", condition, "value", true)));
    }

    private Served fetch(String clientKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/features/" + clientKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String etag = result.getResponse().getHeader("ETag");
        return new Served(etag.replace("\"", ""), readTree(result.getResponse().getContentAsString()));
    }

    private boolean sdkIsOn(JsonNode response, String feature, String attributes) {
        return Boolean.TRUE.equals(new GrowthBook(GBContext.builder().featuresJson(response.path("features").toString())
                .attributesJson(attributes).build()).isOn(feature));
    }

    private void createAttributeIfAbsent(String key, String datatype, boolean hashAttribute, boolean pii) throws Exception {
        int status = mvc.perform(get("/admin/v1/attributes/" + key).with(admin.auth())).andReturn().getResponse().getStatus();
        if (status == 404) {
            admin.postJson("/admin/v1/attributes", Map.of("key", key, "datatype", datatype, "hashAttribute", hashAttribute,
                    "pii", pii), 201);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Setup(String environment, String clientKey, String feature) {
    }

    private record Served(String hash, JsonNode body) {
    }
}
