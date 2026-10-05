package com.ktogroup.ktoggle.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A decision reported by an SDK. Together with the bundle it references it is fully reproducible via replay.
 *
 * @param attributes       only the attributes declared as non-PII
 * @param attributesDigest HMAC of the complete attribute set the SDK evaluated
 */
public record DecisionEvent(UUID eventId, String clientKey, String bundleHash, String featureKey, JsonNode value,
                            String ruleId, String source, String sdk, Instant occurredAt, Instant receivedAt,
                            ObjectNode attributes, String attributesDigest, String digestKeyId) {
}
