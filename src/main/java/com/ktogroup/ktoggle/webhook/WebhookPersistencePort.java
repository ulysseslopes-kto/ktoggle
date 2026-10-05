package com.ktogroup.ktoggle.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookPersistencePort {

    List<Webhook> findAll();

    Optional<Webhook> findById(UUID id);

    void insert(Webhook webhook);

    /** Optimistic update: false when {@code expectedVersion} is stale. */
    boolean update(Webhook webhook, long expectedVersion);

    void delete(UUID id);

    void insertDelivery(WebhookDelivery delivery);

    /**
     * Claims up to {@code limit} due deliveries (pending, due for retry, or stuck in sending past their lock) for this
     * caller, marking them {@code SENDING} until {@code lockedUntil}. Safe with several pods: rows are skipped, not waited on.
     */
    List<WebhookDelivery> claimDue(Instant now, Instant lockedUntil, int limit);

    void markDelivered(UUID id, int statusCode, Instant deliveredAt);

    void markFailed(UUID id, Integer statusCode, String error, int attempts, Instant nextAttemptAt, boolean giveUp);

    List<WebhookDelivery> findDeliveries(UUID webhookId, int limit);

    int deleteDeliveriesBefore(Instant before);
}
