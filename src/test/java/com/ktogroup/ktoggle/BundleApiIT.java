package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.decision.DecisionIngestService;
import com.ktogroup.ktoggle.delivery.DeliveryRecorder;
import com.ktogroup.ktoggle.delivery.SseHub;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class BundleApiIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DeliveryRecorder deliveryRecorder;
    @Autowired
    private DecisionIngestService decisionIngestService;
    @Autowired
    private SseHub sseHub;

    private AdminApi admin;
    private Fixtures fixtures;

    @BeforeEach
    void setUp() {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        fixtures = new Fixtures(admin);
    }

    @Test
    void bundles_and_activations_of_a_connection_can_be_listed_and_inspected() throws Exception {
        Published p = publish();
        fixtures.publishEnvironment(p.feature(), p.environment(), true, List.of(fixtures.forceRule(false, Map.of())));

        JsonNode bundles = admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/bundles");
        assertThat(bundles).hasSize(3);
        assertThat(bundles.get(0).path("clientKey").asText()).isEqualTo(p.clientKey());
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/bundles?limit=1")).hasSize(1);
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/bundles?limit=0")).hasSize(1);
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/bundles?limit=100000")).hasSize(3);

        JsonNode activations = admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/activations");
        assertThat(activations).hasSize(3);
        assertThat(activations.get(0).path("position").asLong()).isEqualTo(3);
        assertThat(activations.get(0).path("prevHash").asText()).isEqualTo(activations.get(1).path("hash").asText());
        assertThat(activations.get(2).path("prevHash").isNull()).isTrue();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/activations?limit=1")).hasSize(1);

        String latest = activations.get(0).path("bundleHash").asText();
        JsonNode verified = admin.getJson("/admin/v1/bundles/" + latest);
        assertThat(verified.path("bundle").path("hash").asText()).isEqualTo(latest);
        assertThat(verified.path("body").path("target").path("clientKey").asText()).isEqualTo(p.clientKey());
        assertThat(verified.path("body").path("sources").path(p.feature()).asInt()).isEqualTo(3);

        JsonNode current = admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/activations/at?instant="
                + Instant.now().plusSeconds(5));
        assertThat(current.path("bundleHash").asText()).isEqualTo(latest);
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/activations/at?instant=2000-01-01T00:00:00Z", 404)
                .path("message").asText()).contains("No bundle was active");

        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/activations/verify").path("checked").asLong())
                .isEqualTo(3);
        admin.getJson("/admin/v1/bundles/" + "0".repeat(64), 404);
        admin.getJson("/admin/v1/sdk-connections/sdk-doesnotexist/bundles", 404);
        admin.getJson("/admin/v1/sdk-connections/sdk-doesnotexist/activations", 404);
        admin.getJson("/admin/v1/sdk-connections/sdk-doesnotexist/activations/verify", 404);
    }

    @Test
    void rollback_rejects_foreign_bundles_and_unpin_requires_a_pin() throws Exception {
        Published first = publish();
        Published second = publish();
        String foreignBundle = latestBundle(second.clientKey());

        assertThat(admin.postJson("/admin/v1/sdk-connections/" + first.clientKey() + "/bundles/" + foreignBundle + "/activate",
                Map.of(), "attempt", 400).path("message").asText()).contains("does not belong");
        admin.postJson("/admin/v1/sdk-connections/" + first.clientKey() + "/bundles/" + "0".repeat(64) + "/activate",
                Map.of(), "attempt", 404);
        admin.postJson("/admin/v1/sdk-connections/sdk-doesnotexist/bundles/" + foreignBundle + "/activate", Map.of(), "attempt", 404);

        assertThat(admin.postJson("/admin/v1/sdk-connections/" + first.clientKey() + "/unpin", Map.of(), 409)
                .path("message").asText()).contains("is not pinned");
        admin.postJson("/admin/v1/sdk-connections/sdk-doesnotexist/unpin", Map.of(), 404);
    }

    @Test
    void replay_of_unknown_bundles_or_instants_is_not_found() throws Exception {
        Published p = publish();
        String hash = latestBundle(p.clientKey());

        admin.postJson("/admin/v1/replay", Map.of("bundleHash", "0".repeat(64), "featureKey", p.feature()), 404);
        admin.postJson("/admin/v1/replay/at", Map.of("clientKey", p.clientKey(), "instant", "2000-01-01T00:00:00Z",
                "featureKey", p.feature()), 404);
        admin.postJson("/admin/v1/replay", Map.of("featureKey", p.feature()), 400);

        JsonNode replay = admin.postJson("/admin/v1/replay", Map.of("bundleHash", hash, "featureKey", p.feature()), 200);
        assertThat(replay.path("clientKey").asText()).isEqualTo(p.clientKey());
        assertThat(replay.path("result").path("value").asBoolean()).isTrue();
        assertThat(replay.path("attributesDigest").asText()).hasSize(64);
        JsonNode unknownFeature = admin.postJson("/admin/v1/replay", Map.of("bundleHash", hash, "featureKey", unique("missing")), 200);
        assertThat(unknownFeature.path("result").path("source").asText()).isEqualTo("unknownFeature");
        assertThat(unknownFeature.path("featureRevision").isNull()).isTrue();
    }

    @Test
    void deliveries_aggregate_polls_and_streams_per_client_key() throws Exception {
        Published p = publish();
        Instant before = Instant.now().minusSeconds(5);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/features/" + p.clientKey()).header("User-Agent", "okhttp/4.11.0 (Linux; Android 14)"));
        }
        mvc.perform(get("/api/features/" + p.clientKey()));
        mvc.perform(get("/sub/" + p.clientKey()).header("User-Agent", "sdk-js/1.0")).andExpect(request().asyncStarted());
        deliveryRecorder.flush();

        JsonNode deliveries = admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/deliveries");
        assertThat(deliveries).as("one row per client key, bundle, channel, pod and hour").hasSize(2);
        JsonNode poll = find(deliveries, "POLL");
        assertThat(poll.path("deliveries").asLong()).isEqualTo(4);
        assertThat(poll.path("bundleHash").asText()).isEqualTo(p.bundleHash());
        assertThat(poll.path("sdkHint").asText()).isEqualTo("okhttp/4.11.0");
        JsonNode stream = find(deliveries, "SSE");
        assertThat(stream.path("deliveries").asLong()).isEqualTo(1);
        assertThat(stream.path("sdkHint").asText()).isEqualTo("sdk-js/1.0");

        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/deliveries?from=" + before + "&to="
                + Instant.now().plusSeconds(5))).hasSize(2);
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/deliveries?to=" + before)).isEmpty();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/deliveries?from="
                + Instant.now().plusSeconds(3600))).isEmpty();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + p.clientKey() + "/deliveries?limit=1")).hasSize(1);
    }

    @Test
    void sse_stream_opens_for_known_client_keys_only_and_survives_heartbeats() throws Exception {
        Published p = publish();

        MvcResult stream = mvc.perform(get("/sub/" + p.clientKey()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        sseHub.heartbeat();

        assertThat(stream.getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/sub/sdk-doesnotexist")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/features/sdk-doesnotexist")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void decision_ingestion_reports_rejections_and_search_filters_stored_events() throws Exception {
        Published p = publish();
        String countryAttribute = ensureNonPiiCountryAttribute();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant now = Instant.now();

        JsonNode result = ingest(p.clientKey(), 202, List.of(
                event(first, p.bundleHash(), p.feature(), now.minusSeconds(60), Map.of(countryAttribute, "BR")),
                event(second, p.bundleHash(), "other_" + p.feature(), now, Map.of()),
                event(UUID.randomUUID(), "not-a-hash", p.feature(), now, Map.of()),
                event(UUID.randomUUID(), p.bundleHash(), " ", now, Map.of()),
                event(UUID.randomUUID(), p.bundleHash(), p.feature(), now.minusSeconds(8 * 24 * 3600), Map.of()),
                event(UUID.randomUUID(), p.bundleHash(), p.feature(), now.plusSeconds(3600), Map.of())));
        assertThat(result.path("accepted").asInt()).isEqualTo(2);
        assertThat(result.path("rejected").asInt()).isEqualTo(4);
        assertThat(result.path("errors")).hasSize(4);
        assertThat(result.path("errors").get(0).asText()).startsWith("events[2]:");
        decisionIngestService.flush();

        String base = "/admin/v1/decisions?clientKey=" + p.clientKey();
        assertThat(admin.getJson(base)).hasSize(2);
        assertThat(admin.getJson(base + "&featureKey=" + p.feature()).get(0).path("attributes").path(countryAttribute).asText())
                .isEqualTo("BR");
        assertThat(admin.getJson(base + "&featureKey=" + p.feature())).hasSize(1);
        assertThat(admin.getJson(base + "&featureKey=" + p.feature()).get(0).path("eventId").asText()).isEqualTo(first.toString());
        assertThat(admin.getJson(base + "&bundleHash=" + p.bundleHash())).hasSize(2);
        assertThat(admin.getJson(base + "&bundleHash=" + "0".repeat(64))).isEmpty();
        assertThat(admin.getJson(base + "&from=" + now.minusSeconds(30))).hasSize(1);
        assertThat(admin.getJson(base + "&to=" + now.minusSeconds(30))).hasSize(1);
        assertThat(admin.getJson(base + "&limit=1")).hasSize(1);
        assertThat(admin.getJson(base + "&limit=0")).hasSize(1);
        assertThat(admin.getJson(base + "&clientKey=sdk-other")).isEmpty();
        admin.getJson("/admin/v1/decisions/" + UUID.randomUUID(), 404);

        JsonNode retried = ingest(p.clientKey(), 202, List.of(
                event(first, p.bundleHash(), p.feature(), now.minusSeconds(60), Map.of(countryAttribute, "BR"))));
        assertThat(retried.path("accepted").asInt()).isEqualTo(1);
        decisionIngestService.flush();
        assertThat(admin.getJson(base)).as("same event id and time are idempotent").hasSize(2);
    }

    @Test
    void decision_ingestion_rejects_unknown_clients_and_oversized_batches() throws Exception {
        Published p = publish();
        int status = mvc.perform(post("/api/decisions/sdk-doesnotexist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[]}")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(404);

        JsonNode empty = ingest(p.clientKey(), 202, List.of());
        assertThat(empty.path("accepted").asInt()).isZero();

        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            events.add(event(null, p.bundleHash(), p.feature(), Instant.now(), Map.of()));
        }
        MvcResult tooMany = mvc.perform(post("/api/decisions/" + p.clientKey()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("events", events)))).andReturn();
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooMany.getResponse().getContentAsString()).contains("At most 1000 events per request");
    }

    /** Shared with KtoggleFlowIT: the ingestion service caches the non-PII allowlist, so it must already list "country". */
    private String ensureNonPiiCountryAttribute() throws Exception {
        if (mvc.perform(get("/admin/v1/attributes/country").with(admin.auth())).andReturn().getResponse().getStatus() == 404) {
            admin.postJson("/admin/v1/attributes", Map.of("key", "country", "datatype", "STRING", "pii", false), 201);
        }
        return "country";
    }

    private Published publish() throws Exception {
        String environment = fixtures.environment();
        String clientKey = fixtures.sdkConnection(environment);
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(fixtures.forceRule(true, Map.of())));
        return new Published(environment, clientKey, feature, latestBundle(clientKey));
    }

    private String latestBundle(String clientKey) throws Exception {
        return admin.getJson("/admin/v1/sdk-connections/" + clientKey + "/activations?limit=1").get(0).path("bundleHash").asText();
    }

    private JsonNode ingest(String clientKey, int expectedStatus, List<Map<String, Object>> events) throws Exception {
        MvcResult result = mvc.perform(post("/api/decisions/" + clientKey).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("events", events)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, Object> event(UUID id, String bundleHash, String feature, Instant occurredAt, Map<String, Object> attributes) {
        Map<String, Object> event = new java.util.HashMap<>();
        if (id != null) {
            event.put("eventId", id);
        }
        event.put("bundleHash", bundleHash);
        event.put("featureKey", feature);
        event.put("value", true);
        event.put("occurredAt", occurredAt.toString());
        event.put("attributes", attributes);
        return event;
    }

    private static JsonNode find(JsonNode deliveries, String channel) {
        for (JsonNode delivery : deliveries) {
            if (channel.equals(delivery.path("channel").asText())) {
                return delivery;
            }
        }
        throw new AssertionError("No %s delivery in %s".formatted(channel, deliveries));
    }

    private record Published(String environment, String clientKey, String feature, String bundleHash) {
    }
}
