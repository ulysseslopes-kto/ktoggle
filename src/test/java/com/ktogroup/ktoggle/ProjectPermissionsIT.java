package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ProjectPermissionsIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private AdminApi admin;
    private AdminApi bob;
    private AdminApi eve;
    private String restricted;
    private String environment;

    @BeforeEach
    void setUp() throws Exception {
        admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        bob = new AdminApi(mvc, objectMapper, "bob", "ktoggle-editor");
        eve = new AdminApi(mvc, objectMapper, "eve", "ktoggle-editor");
        restricted = unique("casino");
        admin.postJson("/admin/v1/projects", Map.of("key", restricted, "name", "Casino", "editorUsers", List.of("bob")), 201);
        environment = new Fixtures(admin).environment();
    }

    @Test
    void only_the_project_editors_change_its_features() throws Exception {
        String feature = unique("lobby");
        eve.postJson("/admin/v1/features", Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false,
                "projectKey", restricted), 403);
        bob.postJson("/admin/v1/features", Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false,
                "projectKey", restricted), 201);

        JsonNode denied = eve.postJson("/admin/v1/features/" + feature + "/drafts", Map.of("title", "x"), 403);
        assertThat(denied.path("message").asText()).contains("Only the editors of project '" + restricted + "'");
        assertThat(eve.getJson("/admin/v1/features/" + feature).path("key").asText()).as("reading stays open").isEqualTo(feature);

        JsonNode published = new Fixtures(bob).publishEnvironment(feature, environment, true, List.of());
        assertThat(published.path("environments").path(environment).path("enabled").asBoolean()).isTrue();

        JsonNode bobsDraft = new Fixtures(bob).draft(feature);
        JsonNode view = eve.getJson("/admin/v1/drafts/" + bobsDraft.path("id").asText());
        assertThat(view.path("permissions").path("canEdit").asBoolean()).isFalse();
        eve.putJson("/admin/v1/drafts/" + bobsDraft.path("id").asText() + "/environments/" + environment,
                Map.of("enabled", false, "version", bobsDraft.path("version").asLong(), "rules", List.of()), 403);
    }

    @Test
    void moving_a_feature_into_a_restricted_project_needs_rights_there() throws Exception {
        String feature = new Fixtures(eve).feature("BOOLEAN", false);
        JsonNode draft = new Fixtures(eve).draft(feature);
        eve.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/metadata", Map.of("projectKey", restricted,
                "defaultValue", false, "tags", List.of(), "archived", false, "version", draft.path("version").asLong()), 403);
    }

    @Test
    void api_tokens_are_listed_as_editors_by_name() throws Exception {
        String name = unique("ci-casino");
        String secret = admin.postJson("/admin/v1/api-tokens", Map.of("name", name, "role", "EDITOR"), 201).path("secret").asText();
        JsonNode project = admin.getJson("/admin/v1/projects/" + restricted);
        admin.putJson("/admin/v1/projects/" + restricted, Map.of("name", "Casino", "editorUsers", List.of("bob", "token:" + name),
                "version", project.path("version").asLong()), 200);

        String feature = unique("table");
        int status = mvc.perform(post("/admin/v1/features").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("key", feature,
                        "valueType", "BOOLEAN", "defaultValue", false, "projectKey", restricted)))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(201);
        assertThat(admin.getJson("/admin/v1/projects/" + restricted).path("editorUsers")).hasSize(2);
    }
}
