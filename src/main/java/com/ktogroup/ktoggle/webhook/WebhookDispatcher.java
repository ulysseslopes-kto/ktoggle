package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.time.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers the outbox. Every pod may run it at the same time: rows are claimed with {@code FOR UPDATE SKIP LOCKED},
 * so each delivery is sent by one pod. A failed delivery is retried with exponential backoff (30s, 1m, 2m, 4m, 8m)
 * and given up after {@value #MAX_ATTEMPTS} attempts; a pod that dies mid-send leaves the row to be reclaimed once its
 * lock expires. Delivery is therefore at-least-once: receivers should de-duplicate on {@code X-Ktoggle-Delivery}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    static final int MAX_ATTEMPTS = 6;
    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration LOCK = Duration.ofMinutes(1);
    static final Duration RETENTION = Duration.ofDays(30);
    private static final int BATCH = 20;
    private static final int MAX_ERROR = 500;

    private final WebhookPersistencePort persistence;
    private final WebhookTransport transport;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${ktoggle.webhooks.dispatch-on-commit:true}")
    private boolean dispatchOnCommit;

    @Scheduled(fixedDelayString = "${ktoggle.webhooks.dispatch-interval:PT5S}", initialDelayString = "PT15S")
    public void scheduled() {
        dispatchSafely();
    }

    /** Called after a commit that enqueued notifications, so they go out within a second instead of the next tick. */
    public void dispatchSoon() {
        if (!dispatchOnCommit) {
            return;
        }
        Thread.ofVirtual().name("webhook-dispatch").start(this::dispatchSafely);
    }

    /** Sends every delivery that is due now; returns how many were attempted. */
    public int dispatchDue() {
        Instant now = Ids.now(clock);
        List<WebhookDelivery> claimed = persistence.claimDue(now, now.plus(LOCK), BATCH);
        Map<UUID, Optional<Webhook>> webhooks = new HashMap<>();
        for (WebhookDelivery delivery : claimed) {
            send(delivery, webhooks.computeIfAbsent(delivery.webhookId(), persistence::findById));
        }
        return claimed.size();
    }

    @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS, initialDelay = 10)
    @SchedulerLock(name = "webhook-retention", lockAtMostFor = "PT10M")
    public void purgeOld() {
        int deleted = persistence.deleteDeliveriesBefore(Ids.now(clock).minus(RETENTION));
        if (deleted > 0) {
            log.info("Deleted {} webhook deliveries older than {}", deleted, RETENTION);
        }
    }

    private void dispatchSafely() {
        try {
            while (dispatchDue() == BATCH) {
                // keep draining while full batches come back
            }
        } catch (RuntimeException e) {
            log.error("Webhook dispatch failed; will retry on the next tick", e);
        }
    }

    private void send(WebhookDelivery delivery, Optional<Webhook> target) {
        int attempts = delivery.attempts() + 1;
        if (target.isEmpty() || !target.get().enabled()) {
            persistence.markFailed(delivery.id(), null, "Webhook disabled", attempts, Ids.now(clock), true);
            return;
        }
        Webhook webhook = target.get();
        Integer status = null;
        String error;
        try {
            String body = objectMapper.writeValueAsString(WebhookMessages.body(webhook.format(), delivery.payload()));
            long timestamp = Ids.now(clock).getEpochSecond();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "ktoggle-webhooks/1");
            headers.put(WebhookSignature.EVENT_HEADER, delivery.event().code());
            headers.put(WebhookSignature.DELIVERY_HEADER, delivery.id().toString());
            headers.put(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp));
            headers.put(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(webhook.secret(), timestamp, body));
            status = transport.post(webhook.url(), headers, body);
            if (status >= 200 && status < 300) {
                persistence.markDelivered(delivery.id(), status, Ids.now(clock));
                meterRegistry.counter("ktoggle.webhooks.deliveries", "result", "delivered").increment();
                return;
            }
            error = "HTTP " + status;
        } catch (JsonProcessingException e) {
            error = "Unserializable payload";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        boolean giveUp = attempts >= MAX_ATTEMPTS;
        Instant next = Ids.now(clock).plus(FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 10)));
        persistence.markFailed(delivery.id(), status, error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error,
                attempts, next, giveUp);
        meterRegistry.counter("ktoggle.webhooks.deliveries", "result", giveUp ? "failed" : "retry").increment();
        log.warn("Webhook {} delivery {} attempt {} failed: {}{}", webhook.name(), delivery.id(), attempts, error,
                giveUp ? " (giving up)" : "");
    }
}
