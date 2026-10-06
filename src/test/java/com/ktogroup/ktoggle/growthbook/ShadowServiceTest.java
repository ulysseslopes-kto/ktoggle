package com.ktogroup.ktoggle.growthbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.delivery.PayloadEncryption;
import org.junit.jupiter.api.Test;

class ShadowServiceTest {

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODw==";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShadowService service = new ShadowService(null, null, null, null, null, null, null, objectMapper, null, null);

    @Test
    void encrypted_growthbook_payloads_are_compared_with_their_saved_groups() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("features");
        payload.put("encryptedFeatures", PayloadEncryption.encrypt("{\"f\":{\"defaultValue\":true}}", KEY));
        payload.put("encryptedSavedGroups", PayloadEncryption.encrypt("{\"grp_1\":[\"u1\"]}", KEY));

        JsonNode decrypted = service.decrypted(payload, KEY);

        assertThat(decrypted.path("features").path("f").path("defaultValue").asBoolean()).isTrue();
        assertThat(decrypted.path("savedGroups").path("grp_1").get(0).asText()).isEqualTo("u1");
    }
}
