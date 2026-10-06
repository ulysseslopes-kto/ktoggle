package com.ktogroup.ktoggle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Import and shadow comparison against a GrowthBook stand-in that serves responses captured from a real GrowthBook
 * (src/test/resources/growthbook). The live check against a running GrowthBook is the local demo (docker compose
 * profile {@code growthbook}).
 */
@IntegrationTest
class GrowthBookMigrationIT {

    private static final String CLIENT_KEY = "sdk-46eVWBICRMOJ8mHs";
    private static final String SECRET = "secret_admin_test";
    private static final HttpServer GROWTHBOOK = start();
    private static final AtomicReference<String> SDK_PAYLOAD = new AtomicReference<>(resource("sdk-payload"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void growthbook(DynamicPropertyRegistry registry) {
        registry.add("ktoggle.growthbook.api-host", () -> "http://127.0.0.1:" + GROWTHBOOK.getAddress().getPort());
        registry.add("ktoggle.growthbook.secret-key", () -> SECRET);
    }

    @AfterAll
    static void stop() {
        GROWTHBOOK.stop(0);
    }

    @Test
    void import_then_shadow_compare_detects_drift_and_reports_readiness() throws Exception {
        AdminApi admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        new AdminApi(mvc, objectMapper, "eddie", "ktoggle-editor").postJson("/admin/v1/growthbook/import", Map.of(), 403);

        JsonNode dryRun = admin.postJson("/admin/v1/growthbook/import", Map.of(), 200);
        assertThat(dryRun.path("dryRun").asBoolean()).isTrue();
        assertThat(dryRun.path("totals").path("FAILED").asInt()).isZero();
        admin.getJson("/admin/v1/features/gb-new-checkout", 404);

        JsonNode applied = admin.postJson("/admin/v1/growthbook/import", Map.of("dryRun", false), 200);
        assertThat(applied.path("totals").path("FAILED").asInt()).as(applied.toString()).isZero();
        assertThat(applied.path("totals").path("CREATE").asInt()).isPositive();
        JsonNode rollout = admin.getJson("/admin/v1/features/gb-new-checkout").path("environments").path("production").path("rules").get(1);
        assertThat(rollout.path("seed").asText()).as("GrowthBook's hashing seed is kept").isEqualTo(rollout.path("id").asText());
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + CLIENT_KEY).path("environmentKey").asText()).isEqualTo("production");

        JsonNode again = admin.postJson("/admin/v1/growthbook/import", Map.of("dryRun", false), 200);
        assertThat(again.path("totals").path("CREATE").asInt() + again.path("totals").path("UPDATE").asInt())
                .as("idempotent: " + again).isZero();

        for (int i = 0; i < 3; i++) {
            JsonNode run = admin.postJson("/admin/v1/shadow/run?clientKey=" + CLIENT_KEY, Map.of(), 200).get(0);
            assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("MATCH");
            assertThat(run.path("samples").asInt()).isEqualTo(2000);
        }
        assertThat(status(admin).path("ready").asBoolean()).isTrue();

        SDK_PAYLOAD.set(SDK_PAYLOAD.get().replace("\"defaultValue\": 1000", "\"defaultValue\": 2000"));
        JsonNode drift = admin.postJson("/admin/v1/shadow/run?clientKey=" + CLIENT_KEY, Map.of(), 200).get(0);
        assertThat(drift.path("status").asText()).isEqualTo("DIVERGENT");
        assertThat(drift.path("divergences").get(0).path("featureKey").asText()).isEqualTo("gb-max-bet");
        JsonNode after = status(admin);
        assertThat(after.path("ready").asBoolean()).isFalse();
        assertThat(after.path("cleanStreak").asInt()).isZero();
        assertThat(admin.getJson("/admin/v1/shadow/" + CLIENT_KEY + "/runs")).hasSize(4);
    }

    private JsonNode status(AdminApi admin) throws Exception {
        for (JsonNode connection : admin.getJson("/admin/v1/shadow")) {
            if (CLIENT_KEY.equals(connection.path("clientKey").asText())) {
                return connection;
            }
        }
        throw new AssertionError("no shadow status for " + CLIENT_KEY);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String collection : List.of("projects", "environments", "attributes", "saved-groups", "features", "sdk-connections")) {
                server.createContext("/api/v1/" + collection, exchange -> {
                    boolean authorized = ("Bearer " + SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, authorized ? 200 : 401, authorized ? resource(collection) : "{}");
                });
            }
            server.createContext("/api/features/", exchange -> respond(exchange,
                    exchange.getRequestURI().getPath().endsWith(CLIENT_KEY) ? 200 : 404,
                    exchange.getRequestURI().getPath().endsWith(CLIENT_KEY) ? SDK_PAYLOAD.get() : "{}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String resource(String name) {
        try (InputStream in = GrowthBookMigrationIT.class.getResourceAsStream("/growthbook/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
