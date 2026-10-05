package com.ktogroup.ktoggle.ztest;

import static com.ktogroup.ktoggle.ztest.AdminApi.unique;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Creates uniquely named reference data through the admin API so integration tests never collide on the shared database. */
public final class Fixtures {

    private final AdminApi admin;

    public Fixtures(AdminApi admin) {
        this.admin = admin;
    }

    public String environment() throws Exception {
        String key = unique("env");
        admin.postJson("/admin/v1/environments", Map.of("key", key, "name", key), 201);
        return key;
    }

    public String project() throws Exception {
        String key = unique("proj");
        admin.postJson("/admin/v1/projects", Map.of("key", key, "name", key), 201);
        return key;
    }

    public String sdkConnection(String environment) throws Exception {
        return admin.postJson("/admin/v1/sdk-connections", Map.of("name", "it", "environmentKey", environment), 201)
                .path("clientKey").asText();
    }

    public String attribute(String prefix, String datatype, boolean hashAttribute, boolean pii) throws Exception {
        String key = unique(prefix).replace("-", "_");
        admin.postJson("/admin/v1/attributes", Map.of("key", key, "datatype", datatype, "hashAttribute", hashAttribute, "pii", pii), 201);
        return key;
    }

    public String feature(String valueType, Object defaultValue) throws Exception {
        String key = unique("feature");
        admin.postJson("/admin/v1/features", Map.of("key", key, "valueType", valueType, "defaultValue", defaultValue), 201);
        return key;
    }

    public long version(String feature) throws Exception {
        return admin.getJson("/admin/v1/features/" + feature).path("version").asLong();
    }

    // ---- Draft workflow: every change to an existing feature is staged in a draft and then published ----

    /** Starts a draft from the live feature (returns the draft; use its {@code version} for the next edit). */
    public JsonNode draft(String feature) throws Exception {
        return admin.postJson("/admin/v1/features/" + feature + "/drafts", Map.of("title", "it"), 201);
    }

    public JsonNode editEnvironment(JsonNode draft, String environment, boolean enabled, List<?> rules) throws Exception {
        return admin.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/environments/" + environment,
                Map.of("enabled", enabled, "version", draft.path("version").asLong(), "rules", rules));
    }

    public JsonNode editMetadata(JsonNode draft, Map<String, Object> overrides) throws Exception {
        JsonNode live = admin.getJson("/admin/v1/features/" + draft.path("featureKey").asText());
        Map<String, Object> body = new HashMap<>();
        for (String field : List.of("projectKey", "defaultValue", "description", "owner", "tags", "archived")) {
            body.put(field, live.get(field));
        }
        body.putAll(overrides);
        body.put("version", draft.path("version").asLong());
        return admin.putJson("/admin/v1/drafts/" + draft.path("id").asText() + "/metadata", body);
    }

    public JsonNode publish(JsonNode draft) throws Exception {
        return admin.postJson("/admin/v1/drafts/" + draft.path("id").asText() + "/publish", Map.of(), 200);
    }

    /** Draft → set the environment → publish; returns the live feature. Test environments do not require review. */
    public JsonNode publishEnvironment(String feature, String environment, boolean enabled, List<?> rules) throws Exception {
        publish(editEnvironment(draft(feature), environment, enabled, rules));
        return admin.getJson("/admin/v1/features/" + feature);
    }

    /** Draft → change the given metadata fields (others keep their live value) → publish; returns the live feature. */
    public JsonNode publishMetadata(String feature, Map<String, Object> overrides) throws Exception {
        publish(editMetadata(draft(feature), overrides));
        return admin.getJson("/admin/v1/features/" + feature);
    }

    /** Draft that reverts the feature to a past revision (not yet published). */
    public JsonNode revert(String feature, int revision) throws Exception {
        return admin.postJson("/admin/v1/features/" + feature + "/revisions/" + revision + "/revert", Map.of(), 201);
    }


    public Map<String, Object> forceRule(Object value, Map<String, Object> condition) {
        return Map.of("type", "force", "enabled", true, "condition", condition, "value", value);
    }
}
