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
     * caller, marking them {@code SENDING} under {@code lockToken} until {@code lockedUntil}. Safe with several pods: rows
     * are skipped, not waited on.
     */
    List<WebhookDelivery> claimDue(Instant now, Instant lockedUntil, UUID lockToken, int limit);

    /**
     * Extends the lock of a delivery right before sending it; false when the claim was lost (the lock expired and the
     * row was claimed again under another token), in which case it must not be sent.
     */
    boolean renewLock(UUID id, UUID lockToken, Instant lockedUntil);

    /** False (nothing changed) when the claim was lost. */
    boolean markDelivered(UUID id, UUID lockToken, int statusCode, Instant deliveredAt);

    /** False (nothing changed) when the claim was lost. */
    boolean markFailed(UUID id, UUID lockToken, Integer statusCode, String error, int attempts, Instant nextAttemptAt,
                       boolean giveUp);

    List<WebhookDelivery> findDeliveries(UUID webhookId, int limit);

    int deleteDeliveriesBefore(Instant before);
}
