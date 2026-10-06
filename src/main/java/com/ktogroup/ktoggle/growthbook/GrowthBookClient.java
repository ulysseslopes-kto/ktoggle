package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/** Read-only access to an existing GrowthBook. Implemented in {@code zout}. */
public interface GrowthBookClient {

    /** REST API v1 collection, all pages: {@code projects}, {@code environments}, {@code features}... */
    List<JsonNode> list(String collection);

    /** {@code GET /api/v1/experiments/{id}} (for experiment-ref rules). */
    Optional<JsonNode> experiment(String id);

    /** What a GrowthBook SDK receives for this client key; empty when GrowthBook does not know the key. */
    Optional<JsonNode> sdkPayload(String clientKey);
}
