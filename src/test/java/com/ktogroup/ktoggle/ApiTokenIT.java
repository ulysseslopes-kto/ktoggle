package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class ApiTokenIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private AdminApi admin;

    @BeforeEach
    void setUp() {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
    }

    @Test
    void an_editor_token_works_on_the_admin_api_and_its_changes_are_attributed_to_it() throws Exception {
        String name = unique("ci");
        JsonNode created = admin.postJson("/admin/v1/api-tokens", Map.of("name", name, "role", "EDITOR"), 201);
        String secret = created.path("secret").asText();
        assertThat(secret).startsWith("ktg_").hasSize(44);
        assertThat(created.path("token").has("tokenHash")).as("the hash is never exposed").isFalse();
        assertThat(created.path("token").path("prefix").asText()).isEqualTo(secret.substring(0, 12));

        assertThat(status(get("/admin/v1/features"), secret)).isEqualTo(200);
        String feature = unique("token-feature");
        assertThat(status(post("/admin/v1/features").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false))),
                secret)).isEqualTo(201);

        JsonNode audit = admin.getJson("/admin/v1/audit?entityType=FEATURE&entityKey=" + feature);
        assertThat(audit.get(0).path("actor").asText()).isEqualTo("token:" + name);
        JsonNode listed = admin.getJson("/admin/v1/api-tokens");
        JsonNode mine = findByName(listed, name);
        assertThat(mine.path("lastUsedAt").isNull()).as("last use is recorded").isFalse();
        assertThat(mine.has("tokenHash")).isFalse();
    }

    @Test
    void tokens_cannot_manage_tokens_or_the_catalog_and_viewers_cannot_write() throws Exception {
        String editor = admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("ed"), "role", "EDITOR"), 201).path("secret").asText();
        String viewer = admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("vw"), "role", "VIEWER"), 201).path("secret").asText();

        assertThat(status(get("/admin/v1/api-tokens"), editor)).isEqualTo(403);
        assertThat(status(post("/admin/v1/environments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"x\",\"name\":\"x\"}"), editor)).isEqualTo(403);
        assertThat(status(get("/admin/v1/features"), viewer)).isEqualTo(200);
        assertThat(status(post("/admin/v1/features").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"nope\",\"valueType\":\"BOOLEAN\",\"defaultValue\":false}"), viewer)).isEqualTo(403);
        admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("adm"), "role", "ADMIN"), 400);
    }

    @Test
    void tokens_never_review_drafts() throws Exception {
        String secret = admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("bot"), "role", "EDITOR"), 201)
                .path("secret").asText();
        String feature = new Fixtures(admin).feature("BOOLEAN", false);
        JsonNode draft = new Fixtures(admin).draft(feature);
        admin.postJson("/admin/v1/drafts/" + draft.path("id").asText() + "/request-review", Map.of(), 200);

        MvcResult approve = mvc.perform(withToken(post("/admin/v1/drafts/" + draft.path("id").asText() + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), secret)).andReturn();
        assertThat(approve.getResponse().getStatus()).isEqualTo(403);
        assertThat(approve.getResponse().getContentAsString()).contains("API tokens cannot review drafts");
    }

    @Test
    void revoked_expired_and_unknown_tokens_are_rejected() throws Exception {
        String name = unique("tmp");
        JsonNode created = admin.postJson("/admin/v1/api-tokens", Map.of("name", name, "role", "VIEWER",
                "expiresAt", Instant.now().plus(Duration.ofDays(30)).toString()), 201);
        String secret = created.path("secret").asText();
        assertThat(status(get("/admin/v1/features"), secret)).isEqualTo(200);

        admin.postJson("/admin/v1/api-tokens", Map.of("name", name, "role", "VIEWER"), 409);
        String id = created.path("token").path("id").asText();
        MvcResult revoked = mvc.perform(delete("/admin/v1/api-tokens/" + id).with(admin.auth())).andReturn();
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(status(get("/admin/v1/features"), secret)).isEqualTo(401);
        assertThat(admin.getJson("/admin/v1/audit?entityType=API_TOKEN&entityKey=" + name)).hasSize(2);

        assertThat(status(get("/admin/v1/features"), "ktg_" + "x".repeat(40))).isEqualTo(401);
        admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("old"), "role", "VIEWER",
                "expiresAt", Instant.now().minusSeconds(60).toString()), 400);
        admin.postJson("/admin/v1/api-tokens", Map.of("name", unique("forever"), "role", "VIEWER",
                "expiresAt", Instant.now().plus(Duration.ofDays(400)).toString()), 400);
        assertThat(admin.postJson("/admin/v1/api-tokens", Map.of("name", name, "role", "VIEWER"), 201).path("secret").asText())
                .as("a revoked name can be reused").startsWith("ktg_");
    }

    private int status(MockHttpServletRequestBuilder request, String secret) throws Exception {
        return mvc.perform(withToken(request, secret)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String secret) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    private static JsonNode findByName(JsonNode tokens, String name) {
        for (JsonNode token : tokens) {
            if (name.equals(token.path("name").asText())) {
                return token;
            }
        }
        throw new AssertionError("token " + name + " not listed");
    }
}
