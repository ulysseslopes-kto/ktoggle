package com.ktogroup.ktoggle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.delivery.DeliveryRecorder;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class RemoteEvalIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DeliveryRecorder deliveryRecorder;

    @Test
    void remote_eval_connections_return_values_and_never_the_rules() throws Exception {
        AdminApi admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        Fixtures fixtures = new Fixtures(admin);
        String environment = fixtures.environment();
        admin.postJson("/admin/v1/attributes", Map.of("key", "tier", "datatype", "STRING"), 201);
        String remoteKey = admin.postJson("/admin/v1/sdk-connections", Map.of("name", "web", "environmentKey", environment,
                "remoteEval", true), 201).path("clientKey").asText();
        String localKey = fixtures.sdkConnection(environment);
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(Map.of("type", "force", "enabled", true,
                "condition", Map.of("tier", "gold"), "value", true)));

        JsonNode plain = getJson("/api/features/" + remoteKey);
        assertThat(plain.path("features")).as("the rules are not served to remote-eval connections").isEmpty();

        JsonNode gold = eval(remoteKey, Map.of("attributes", Map.of("tier", "gold")), 200);
        assertThat(gold.path("features").path(feature).path("defaultValue").asBoolean()).isTrue();
        assertThat(gold.path("features").path(feature).has("rules")).isFalse();
        assertThat(gold.toString()).doesNotContain("\"tier\"");
        assertThat(gold.path("bundleHash").asText()).isEqualTo(plain.path("bundleHash").asText());
        assertThat(eval(remoteKey, Map.of("attributes", Map.of("tier", "silver")), 200).path("features").path(feature)
                .path("defaultValue").asBoolean()).isFalse();
        assertThat(eval(remoteKey, Map.of("attributes", Map.of(), "forcedFeatures", List.of(List.of(feature, true))), 200)
                .path("features").path(feature).path("defaultValue").asBoolean()).as("forced features (QA)").isTrue();

        assertThat(eval(localKey, Map.of("attributes", Map.of()), 400).path("message").asText())
                .contains("Remote evaluation is not enabled");
        JsonNode connection = admin.getJson("/admin/v1/sdk-connections/" + remoteKey);
        admin.putJson("/admin/v1/sdk-connections/" + remoteKey, Map.of("name", "web", "encryptPayload", true,
                "version", connection.path("version").asLong()), 400);
        deliveryRecorder.flush();
        assertThat(admin.getJson("/admin/v1/sdk-connections/" + remoteKey + "/deliveries").toString()).contains("REMOTE_EVAL");
    }

    private JsonNode getJson(String path) throws Exception {
        return objectMapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode eval(String clientKey, Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/eval/" + clientKey).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
