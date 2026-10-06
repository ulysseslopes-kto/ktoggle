package com.ktogroup.ktoggle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.webhook.WebhookDelivery;
import com.ktogroup.ktoggle.webhook.WebhookDispatcher;
import com.ktogroup.ktoggle.webhook.WebhookPersistencePort;
import com.ktogroup.ktoggle.webhook.WebhookSignature;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class WebhookIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookPersistencePort persistence;

    private AdminApi admin;
    private HttpServer receiver;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger responseStatus = new AtomicInteger(204);
    private final List<String> created = new ArrayList<>();

    record Received(Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            received.add(new Received(headers, body));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String id : created) {
            admin.delete("/admin/v1/webhooks/" + id, 204);
        }
        receiver.stop(0);
    }

    @Test
    void publishing_a_draft_sends_a_signed_notification() throws Exception {
        JsonNode webhook = create(List.of("draft.review_requested", "draft.published"), "GENERIC");
        String secret = webhook.path("secret").asText();
        assertThat(secret).startsWith("whsec_");
        assertThat(admin.getJson("/admin/v1/webhooks").toString()).as("the secret is never listed").doesNotContain(secret);

        Fixtures fixtures = new Fixtures(admin);
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, fixtures.environment(), true, List.of());
        drain();

        Received published = received.stream().filter(r -> "draft.published".equals(r.headers().get("x-ktoggle-event")))
                .filter(r -> r.body().contains(feature)).findFirst().orElseThrow();
        long timestamp = Long.parseLong(published.headers().get("x-ktoggle-timestamp"));
        assertThat(WebhookSignature.verify(secret, timestamp, published.body(), published.headers().get("x-ktoggle-signature")))
                .as("receivers can verify the origin").isTrue();
        JsonNode payload = objectMapper.readTree(published.body());
        assertThat(payload.path("actor").asText()).isEqualTo("alice");
        assertThat(payload.path("data").path("featureKey").asText()).isEqualTo(feature);
        assertThat(payload.path("summary").asText()).contains("alice published " + feature);
        assertThat(payload.path("link").asText()).contains("/features/" + feature);
        assertThat(published.headers().get("x-ktoggle-delivery")).isEqualTo(payload.path("id").asText());

        JsonNode deliveries = admin.getJson("/admin/v1/webhooks/" + webhook.path("webhook").path("id").asText() + "/deliveries");
        assertThat(deliveries.get(0).path("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void failed_deliveries_are_retried_later_and_disabled_webhooks_receive_nothing() throws Exception {
        responseStatus.set(500);
        JsonNode webhook = create(List.of("draft.published"), "SLACK");
        String id = webhook.path("webhook").path("id").asText();
        admin.postJson("/admin/v1/webhooks/" + id + "/test", Map.of(), 202);
        drain();

        JsonNode failed = admin.getJson("/admin/v1/webhooks/" + id + "/deliveries").get(0);
        assertThat(failed.path("status").asText()).isEqualTo("RETRY");
        assertThat(failed.path("attempts").asInt()).isEqualTo(1);
        assertThat(failed.path("lastStatusCode").asInt()).isEqualTo(500);
        assertThat(received.getLast().body()).as("Slack format").contains("\"text\":\"*ktoggle*");

        int before = received.size();
        admin.putJson("/admin/v1/webhooks/" + id, Map.of("name", "off", "url", url(), "format", "SLACK",
                "events", List.of("draft.published"), "enabled", false, "version", 0), 200);
        new Fixtures(admin).publishMetadata(new Fixtures(admin).feature("BOOLEAN", false), Map.of("description", "x"));
        drain();
        assertThat(received).hasSize(before);
    }

    @Test
    void webhooks_are_admin_only_and_validated() throws Exception {
        new AdminApi(mvc, objectMapper, "eddie", "ktoggle-editor").getJson("/admin/v1/webhooks", 403);
        admin.postJson("/admin/v1/webhooks", Map.of("name", "x", "url", "ftp://example.com", "events", List.of("draft.published")), 400);
        admin.postJson("/admin/v1/webhooks", Map.of("name", "x", "url", url(), "events", List.of()), 400);
        assertThat(admin.getJson("/admin/v1/webhooks/events")).extracting(e -> e.path("code").asText())
                .contains("draft.published", "bundle.rolled_back").doesNotContain("webhook.test");
    }

    @Test
    void a_claim_whose_lock_expired_and_was_taken_over_can_neither_send_nor_record_the_delivery() throws Exception {
        String id = create(List.of("draft.published"), "GENERIC").path("webhook").path("id").asText();
        admin.postJson("/admin/v1/webhooks/" + id + "/test", Map.of(), 202);
        Instant now = Instant.now();
        // locks already expired, so whatever else these claims pick up stays reclaimable by the dispatcher
        UUID slow = UUID.randomUUID();
        UUID delivery = persistence.claimDue(now, now.minusSeconds(1), slow, 100).stream()
                .filter(d -> d.webhookId().toString().equals(id)).findFirst().orElseThrow().id();
        UUID takeover = UUID.randomUUID();
        assertThat(persistence.claimDue(now.plusMillis(1), now, takeover, 100)).extracting(WebhookDelivery::id).contains(delivery);

        assertThat(persistence.renewLock(delivery, slow, now.plusSeconds(60))).as("the slow pod must not send it").isFalse();
        assertThat(persistence.markFailed(delivery, slow, 500, "late", 1, now, false)).isFalse();
        assertThat(persistence.markDelivered(delivery, slow, 200, now)).isFalse();
        assertThat(persistence.markDelivered(delivery, takeover, 200, now)).isTrue();
        assertThat(admin.getJson("/admin/v1/webhooks/" + id + "/deliveries").get(0).path("status").asText()).isEqualTo("DELIVERED");
    }

    private JsonNode create(List<String> events, String format) throws Exception {
        JsonNode result = admin.postJson("/admin/v1/webhooks", Map.of("name", "it", "url", url(), "format", format,
                "events", events), 201);
        created.add(result.path("webhook").path("id").asText());
        return result;
    }

    private String url() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private void drain() {
        while (dispatcher.dispatchDue() > 0) {
            // all due deliveries are attempted once
        }
    }
}
