package com.ktogroup.ktoggle;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@IntegrationTest
class AdminApiIT {

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
    void project_lifecycle_rejects_duplicates_stale_versions_and_invalid_input() throws Exception {
        String key = unique("proj");
        JsonNode created = admin.postJson("/admin/v1/projects", Map.of("key", key, "name", "Checkout", "description", "d"), 201);
        assertThat(created.path("key").asText()).isEqualTo(key);

        assertThat(admin.postJson("/admin/v1/projects", Map.of("key", key, "name", "again"), 409).path("messageCode").asText())
                .isEqualTo("ENTITY_ALREADY_EXISTS");
        assertThat(admin.postJson("/admin/v1/projects", Map.of("key", "not a key!", "name", "x"), 400).path("messageCode").asText())
                .isEqualTo("VALIDATION_ERROR");
        JsonNode blankName = admin.postJson("/admin/v1/projects", Map.of("key", unique("p"), "name", " "), 400);
        assertThat(blankName.path("message").asText()).isEqualTo("Invalid request");
        assertThat(blankName.path("data").get(0).asText()).startsWith("name:");
        JsonNode malformed = readTree(admin.perform(post("/admin/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{"), 400)
                .getResponse().getContentAsString());
        assertThat(malformed.path("message").asText()).isEqualTo("Malformed request body");

        long version = created.path("version").asLong();
        JsonNode updated = admin.putJson("/admin/v1/projects/" + key, Map.of("name", "Checkout v2", "version", version));
        assertThat(updated.path("name").asText()).isEqualTo("Checkout v2");
        assertThat(updated.path("version").asLong()).isGreaterThan(version);
        assertThat(admin.putJson("/admin/v1/projects/" + key, Map.of("name", "stale", "version", version), 409)
                .path("messageCode").asText()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(admin.putJson("/admin/v1/projects/" + key, Map.of("name", "no version"), 400).path("message").asText())
                .contains("version is required");
        assertThat(admin.putJson("/admin/v1/projects/" + unique("missing"), Map.of("name", "x", "version", 0), 404)
                .path("messageCode").asText()).isEqualTo("ENTITY_NOT_FOUND");

        assertThat(admin.getJson("/admin/v1/projects")).anySatisfy(p -> assertThat(p.path("key").asText()).isEqualTo(key));
        admin.delete("/admin/v1/projects/" + key, 204);
        admin.getJson("/admin/v1/projects/" + key, 404);
    }

    @Test
    void project_in_use_by_a_feature_or_an_sdk_connection_cannot_be_deleted() throws Exception {
        String byFeature = fixtures.project();
        String feature = unique("feature");
        admin.postJson("/admin/v1/features", Map.of("key", feature, "valueType", "BOOLEAN", "defaultValue", false,
                "projectKey", byFeature), 201);
        assertThat(admin.delete("/admin/v1/projects/" + byFeature, 409).path("messageCode").asText()).isEqualTo("ENTITY_IN_USE");

        String byConnection = fixtures.project();
        admin.postJson("/admin/v1/sdk-connections", Map.of("name", "scoped", "environmentKey", fixtures.environment(),
                "projectKeys", List.of(byConnection)), 201);
        admin.delete("/admin/v1/projects/" + byConnection, 409);
        admin.delete("/admin/v1/projects/" + unique("missing"), 404);
    }

    @Test
    void environment_lifecycle_and_in_use_protection() throws Exception {
        String key = unique("env");
        JsonNode created = admin.postJson("/admin/v1/environments", Map.of("key", key, "name", "Staging", "sortOrder", 3), 201);
        assertThat(created.path("sortOrder").asInt()).isEqualTo(3);
        admin.postJson("/admin/v1/environments", Map.of("key", key, "name", "dup"), 409);
        admin.postJson("/admin/v1/environments", Map.of("key", "bad key", "name", "x"), 400);

        long version = created.path("version").asLong();
        JsonNode updated = admin.putJson("/admin/v1/environments/" + key,
                Map.of("name", "Staging 2", "description", "d", "sortOrder", 5, "version", version));
        assertThat(updated.path("name").asText()).isEqualTo("Staging 2");
        assertThat(updated.path("sortOrder").asInt()).isEqualTo(5);
        admin.putJson("/admin/v1/environments/" + key, Map.of("name", "stale", "version", version), 409);
        admin.putJson("/admin/v1/environments/" + key, Map.of("name", "no version"), 400);
        assertThat(admin.getJson("/admin/v1/environments")).anySatisfy(e -> assertThat(e.path("key").asText()).isEqualTo(key));

        String withConnection = fixtures.environment();
        fixtures.sdkConnection(withConnection);
        assertThat(admin.delete("/admin/v1/environments/" + withConnection, 409).path("messageCode").asText())
                .isEqualTo("ENTITY_IN_USE");

        String withFeature = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, withFeature, true, List.of());
        admin.delete("/admin/v1/environments/" + withFeature, 409);

        admin.delete("/admin/v1/environments/" + key, 204);
        admin.getJson("/admin/v1/environments/" + key, 404);
        admin.delete("/admin/v1/environments/" + key, 404);
    }

    @Test
    void attributes_are_validated_versioned_and_private_by_default() throws Exception {
        String key = unique("attr").replace("-", "_");
        JsonNode created = admin.postJson("/admin/v1/attributes", Map.of("key", key, "datatype", "STRING"), 201);
        assertThat(created.path("pii").asBoolean()).as("privacy by default").isTrue();

        admin.postJson("/admin/v1/attributes", Map.of("key", key, "datatype", "STRING"), 409);
        admin.postJson("/admin/v1/attributes", Map.of("key", "bad key", "datatype", "STRING"), 400);
        assertThat(admin.postJson("/admin/v1/attributes", Map.of("key", unique("e"), "datatype", "ENUM"), 400).path("message").asText())
                .contains("enumValues");
        assertThat(admin.postJson("/admin/v1/attributes", Map.of("key", unique("b"), "datatype", "BOOLEAN", "hashAttribute", true), 400)
                .path("message").asText()).contains("hash attributes");
        admin.postJson("/admin/v1/attributes", Map.of("key", unique("t")), 400);

        JsonNode enumAttribute = admin.postJson("/admin/v1/attributes",
                Map.of("key", unique("plan"), "datatype", "ENUM", "enumValues", List.of("free", "pro"), "pii", false), 201);
        assertThat(enumAttribute.path("enumValues")).hasSize(2);

        long version = created.path("version").asLong();
        JsonNode updated = admin.putJson("/admin/v1/attributes/" + key,
                Map.of("datatype", "NUMBER", "hashAttribute", true, "pii", false, "archived", true, "version", version));
        assertThat(updated.path("datatype").asText()).isEqualTo("NUMBER");
        assertThat(updated.path("archived").asBoolean()).isTrue();
        assertThat(updated.path("pii").asBoolean()).isFalse();
        admin.putJson("/admin/v1/attributes/" + key, Map.of("datatype", "STRING", "version", version), 409);
        admin.putJson("/admin/v1/attributes/" + key, Map.of("datatype", "STRING"), 400);
        admin.putJson("/admin/v1/attributes/" + key, Map.of("datatype", "ENUM", "version", updated.path("version").asLong()), 400);

        assertThat(admin.getJson("/admin/v1/attributes")).anySatisfy(a -> assertThat(a.path("key").asText()).isEqualTo(key));
        admin.getJson("/admin/v1/attributes/" + unique("missing"), 404);
    }

    @Test
    void list_saved_group_lifecycle_and_validation() throws Exception {
        String attribute = fixtures.attribute("country", "STRING", false, false);
        String key = unique("sg");
        JsonNode created = admin.postJson("/admin/v1/saved-groups", Map.of("key", key, "name", "Latam", "type", "LIST",
                "attributeKey", attribute, "values", List.of("BR", "AR", 7)), 201);
        assertThat(created.path("values")).hasSize(3);

        admin.postJson("/admin/v1/saved-groups", Map.of("key", key, "name", "dup", "type", "LIST", "attributeKey", attribute,
                "values", List.of("x")), 409);
        admin.postJson("/admin/v1/saved-groups", Map.of("key", "bad key", "name", "x", "type", "LIST", "attributeKey", attribute,
                "values", List.of("x")), 400);
        assertThat(admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "LIST",
                "values", List.of("x")), 400).path("message").asText()).contains("attributeKey and values");
        admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "LIST",
                "attributeKey", unique("missing"), "values", List.of("x")), 404);
        assertThat(admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "LIST",
                "attributeKey", attribute, "values", List.of(List.of("nested"))), 400).path("message").asText())
                .contains("strings or numbers");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= 10_000; i++) {
            tooMany.add("v" + i);
        }
        assertThat(admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "LIST",
                "attributeKey", attribute, "values", tooMany), 400).path("message").asText()).contains("limited to 10000");

        long version = created.path("version").asLong();
        JsonNode updated = admin.putJson("/admin/v1/saved-groups/" + key, Map.of("name", "Latam 2", "type", "LIST",
                "attributeKey", attribute, "values", List.of("BR"), "version", version));
        assertThat(updated.path("values")).hasSize(1);
        admin.putJson("/admin/v1/saved-groups/" + key, Map.of("name", "stale", "type", "LIST", "attributeKey", attribute,
                "values", List.of("BR"), "version", version), 409);
        admin.putJson("/admin/v1/saved-groups/" + key, Map.of("name", "x", "type", "LIST", "attributeKey", attribute,
                "values", List.of("BR")), 400);
        assertThat(admin.putJson("/admin/v1/saved-groups/" + key, Map.of("name", "x", "type", "CONDITION",
                "condition", Map.of(attribute, "BR"), "version", updated.path("version").asLong()), 400).path("message").asText())
                .contains("cannot change");

        assertThat(admin.getJson("/admin/v1/saved-groups")).anySatisfy(g -> assertThat(g.path("key").asText()).isEqualTo(key));
        admin.getJson("/admin/v1/saved-groups/" + unique("missing"), 404);
        admin.delete("/admin/v1/saved-groups/" + key, 204);
        admin.getJson("/admin/v1/saved-groups/" + key, 404);
    }

    @Test
    void condition_saved_group_requires_a_valid_non_empty_condition() throws Exception {
        String attribute = fixtures.attribute("tier", "STRING", false, false);
        String key = unique("sg");
        JsonNode created = admin.postJson("/admin/v1/saved-groups", Map.of("key", key, "name", "Gold", "type", "CONDITION",
                "condition", Map.of(attribute, "gold")), 201);
        assertThat(created.path("condition").path(attribute).asText()).isEqualTo("gold");
        assertThat(created.path("attributeKey").isNull()).isTrue();

        assertThat(admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "CONDITION",
                "condition", Map.of()), 400).path("message").asText()).contains("non-empty condition");
        assertThat(admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "CONDITION",
                "condition", Map.of("unknown_" + UUID.randomUUID().toString().replace("-", ""), "x")), 400)
                .path("messageCode").asText()).isEqualTo("INVALID_CONDITION");
        admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x", "type", "CONDITION",
                "condition", List.of("not", "an", "object")), 400);
        admin.postJson("/admin/v1/saved-groups", Map.of("key", unique("sg"), "name", "x"), 400);

        JsonNode updated = admin.putJson("/admin/v1/saved-groups/" + key, Map.of("name", "Gold+", "type", "CONDITION",
                "condition", Map.of(attribute, Map.of("$in", List.of("gold", "platinum"))), "version", created.path("version").asLong()));
        assertThat(updated.path("condition").path(attribute).path("$in")).hasSize(2);
    }

    @Test
    void saved_group_referenced_by_a_rule_cannot_be_deleted() throws Exception {
        String attribute = fixtures.attribute("country", "STRING", false, false);
        String group = unique("sg");
        admin.postJson("/admin/v1/saved-groups", Map.of("key", group, "name", "g", "type", "LIST", "attributeKey", attribute,
                "values", List.of("BR")), 201);
        String environment = fixtures.environment();
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(
                Map.of("type", "force", "enabled", true, "value", true, "savedGroups", List.of(group))));

        assertThat(admin.delete("/admin/v1/saved-groups/" + group, 409).path("messageCode").asText()).isEqualTo("ENTITY_IN_USE");

        fixtures.publishEnvironment(feature, environment, true, List.of());
        admin.delete("/admin/v1/saved-groups/" + group, 204);
        admin.delete("/admin/v1/saved-groups/" + group, 404);
    }

    @Test
    void sdk_connection_lifecycle_and_validation() throws Exception {
        String environment = fixtures.environment();
        String projectB = fixtures.project();
        String projectA = fixtures.project();
        String clientKey = "sdk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        JsonNode created = admin.postJson("/admin/v1/sdk-connections", Map.of("clientKey", clientKey, "name", "web",
                "environmentKey", environment, "projectKeys", List.of(projectB, projectA, projectA)), 201);
        assertThat(created.path("clientKey").asText()).isEqualTo(clientKey);
        List<String> expectedProjects = List.of(projectA, projectB).stream().sorted().toList();
        assertThat(created.path("projectKeys")).extracting(JsonNode::asText).containsExactlyElementsOf(expectedProjects);

        admin.postJson("/admin/v1/sdk-connections", Map.of("clientKey", clientKey, "name", "dup", "environmentKey", environment), 409);
        assertThat(admin.postJson("/admin/v1/sdk-connections", Map.of("clientKey", "nope", "name", "x",
                "environmentKey", environment), 400).path("message").asText()).contains("clientKey must match");
        assertThat(admin.postJson("/admin/v1/sdk-connections", Map.of("name", "x"), 400).path("message").asText())
                .contains("environmentKey is required");
        admin.postJson("/admin/v1/sdk-connections", Map.of("name", "x", "environmentKey", unique("missing")), 404);
        admin.postJson("/admin/v1/sdk-connections", Map.of("name", "x", "environmentKey", environment,
                "projectKeys", List.of(unique("missing"))), 404);

        JsonNode generated = admin.postJson("/admin/v1/sdk-connections", Map.of("name", "generated", "environmentKey", environment), 201);
        assertThat(generated.path("clientKey").asText()).matches("^sdk-[A-Za-z0-9]{16}$");
        assertThat(generated.path("projectKeys")).isEmpty();

        long version = created.path("version").asLong();
        JsonNode updated = admin.putJson("/admin/v1/sdk-connections/" + clientKey,
                Map.of("name", "web v2", "projectKeys", List.of(), "version", version));
        assertThat(updated.path("name").asText()).isEqualTo("web v2");
        assertThat(updated.path("projectKeys")).isEmpty();
        assertThat(updated.path("environmentKey").asText()).isEqualTo(environment);
        admin.putJson("/admin/v1/sdk-connections/" + clientKey, Map.of("name", "stale", "version", version), 409);
        admin.putJson("/admin/v1/sdk-connections/" + clientKey, Map.of("name", "no version"), 400);

        assertThat(admin.getJson("/admin/v1/sdk-connections")).anySatisfy(c -> assertThat(c.path("clientKey").asText()).isEqualTo(clientKey));
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + clientKey).path("name").asText()).isEqualTo("web v2");
        admin.getJson("/admin/v1/sdk-connections/sdk-doesnotexist", 404);
    }

    @Test
    void audit_trail_can_be_filtered_and_paginated() throws Exception {
        String key = unique("proj");
        Instant before = Instant.now().minusSeconds(1);
        admin.postJson("/admin/v1/projects", Map.of("key", key, "name", "audited"), "because", 201);
        JsonNode project = admin.getJson("/admin/v1/projects/" + key);
        admin.putJson("/admin/v1/projects/" + key, Map.of("name", "audited 2", "version", project.path("version").asLong()));
        Instant after = Instant.now().plusSeconds(1);

        JsonNode entries = admin.getJson("/admin/v1/audit?entityType=PROJECT&entityKey=" + key);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("action").asText()).isEqualTo("UPDATE");
        assertThat(entries.get(0).path("before").path("name").asText()).isEqualTo("audited");
        assertThat(entries.get(1).path("action").asText()).isEqualTo("CREATE");
        assertThat(entries.get(1).path("reason").asText()).isEqualTo("because");
        assertThat(entries.get(0).path("seq").asLong()).isGreaterThan(entries.get(1).path("seq").asLong());

        UUID changeId = UUID.fromString(entries.get(1).path("changeId").asText());
        assertThat(admin.getJson("/admin/v1/audit?changeId=" + changeId)).hasSize(1);
        assertThat(admin.getJson("/admin/v1/audit?entityKey=" + key + "&actor=alice")).hasSize(2);
        assertThat(admin.getJson("/admin/v1/audit?entityKey=" + key + "&actor=somebody-else")).isEmpty();
        assertThat(admin.getJson("/admin/v1/audit?entityKey=" + key + "&from=" + before + "&to=" + after)).hasSize(2);
        assertThat(admin.getJson("/admin/v1/audit?entityKey=" + key + "&to=" + before)).isEmpty();
        assertThat(admin.getJson("/admin/v1/audit?entityKey=" + key + "&limit=1")).hasSize(1);
        long newest = entries.get(0).path("seq").asLong();
        JsonNode older = admin.getJson("/admin/v1/audit?entityKey=" + key + "&beforeSeq=" + newest);
        assertThat(older).hasSize(1);
        assertThat(older.get(0).path("action").asText()).isEqualTo("CREATE");
        assertThat(admin.getJson("/admin/v1/audit/verify").path("valid").asBoolean()).isTrue();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
