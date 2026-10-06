package com.ktogroup.ktoggle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.ztest.AdminApi;
import com.ktogroup.ktoggle.ztest.Fixtures;
import com.ktogroup.ktoggle.ztest.IntegrationTest;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.GBContext;
import growthbook.sdk.java.util.DecryptionUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class EncryptedPayloadIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void encrypted_connections_serve_encrypted_features_readable_with_the_key_and_rotation_takes_effect() throws Exception {
        AdminApi admin = new AdminApi(mvc, objectMapper, "alice", "ktoggle-admin");
        Fixtures fixtures = new Fixtures(admin);
        String environment = fixtures.environment();
        JsonNode connection = admin.postJson("/admin/v1/sdk-connections", Map.of("name", "app", "environmentKey", environment,
                "encryptPayload", true), 201);
        String clientKey = connection.path("clientKey").asText();
        assertThat(connection.path("encryptPayload").asBoolean()).isTrue();
        assertThat(connection.has("decryptionKey")).as("the key is never in regular responses").isFalse();
        String feature = fixtures.feature("BOOLEAN", false);
        fixtures.publishEnvironment(feature, environment, true, List.of(Map.of("type", "force", "enabled", true, "value", true)));

        JsonNode served = fetch(clientKey);
        assertThat(served.path("features")).isEmpty();
        assertThat(served.toString()).doesNotContain(feature);
        String key = admin.getJson("/admin/v1/sdk-connections/" + clientKey + "/decryption-key").path("decryptionKey").asText();
        assertThat(sdkIsOn(served, key, feature)).isTrue();
        new AdminApi(mvc, objectMapper, "victor", "ktoggle-viewer").getJson("/admin/v1/sdk-connections/" + clientKey
                + "/decryption-key", 403);

        String etag = mvc.perform(get("/api/features/" + clientKey)).andReturn().getResponse().getHeader("ETag");
        JsonNode rotated = admin.postJson("/admin/v1/sdk-connections/" + clientKey + "/rotate-key", Map.of(), 200);
        assertThat(rotated.path("keyFingerprint").asText()).isNotEqualTo(connection.path("keyFingerprint").asText());
        String newKey = admin.getJson("/admin/v1/sdk-connections/" + clientKey + "/decryption-key").path("decryptionKey").asText();
        JsonNode afterRotation = fetch(clientKey);
        assertThat(sdkIsOn(afterRotation, newKey, feature)).isTrue();
        assertThat(mvc.perform(get("/api/features/" + clientKey)).andReturn().getResponse().getHeader("ETag"))
                .as("same bundle, but a new key: clients must not get a 304 with the old body").isNotEqualTo(etag);
        assertThat(decrypts(afterRotation, key)).as("the old key no longer works").isFalse();

        admin.putJson("/admin/v1/sdk-connections/" + clientKey, Map.of("name", "app", "encryptPayload", false,
                "version", rotated.path("version").asLong()), 200);
        assertThat(fetch(clientKey).path("features").has(feature)).as("back in clear text").isTrue();

        JsonNode audit = admin.getJson("/admin/v1/audit?entityType=SDK_CONNECTION&entityKey=" + clientKey);
        assertThat(audit.toString()).doesNotContain(key).doesNotContain(newKey).contains("keyFingerprint");
    }

    private JsonNode fetch(String clientKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/features/" + clientKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static boolean sdkIsOn(JsonNode response, String key, String feature) throws Exception {
        String features = DecryptionUtils.decrypt(response.path("encryptedFeatures").asText(), key).trim();
        return Boolean.TRUE.equals(new GrowthBook(GBContext.builder().featuresJson(features).attributesJson("{}").build())
                .isOn(feature));
    }

    private static boolean decrypts(JsonNode response, String key) {
        try {
            String text = DecryptionUtils.decrypt(response.path("encryptedFeatures").asText(), key).trim();
            return text.startsWith("{");
        } catch (Exception e) {
            return false;
        }
    }
}
