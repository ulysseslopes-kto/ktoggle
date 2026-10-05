package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** One notification to one webhook, from the outbox. {@code payload} is the generic ktoggle payload. */
public record WebhookDelivery(UUID id, UUID webhookId, WebhookEvent event, JsonNode payload, Status status, int attempts,
                              Instant nextAttemptAt, Integer lastStatusCode, String lastError, Instant createdAt,
                              Instant deliveredAt) {

    public enum Status {
        /** Waiting for its first attempt. */
        PENDING,
        /** Claimed by a pod and being sent. */
        SENDING,
        /** Failed, will be retried at {@code nextAttemptAt}. */
        RETRY,
        DELIVERED,
        /** Gave up after the last retry. */
        FAILED
    }
}
