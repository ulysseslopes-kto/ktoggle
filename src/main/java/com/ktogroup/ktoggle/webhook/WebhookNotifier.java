package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Entry point for the rest of ktoggle: writes one outbox row per subscribed webhook, in the caller's transaction, so a
 * notification exists if and only if the change it announces was committed. Delivery happens afterwards.
 */
@Component
@RequiredArgsConstructor
public class WebhookNotifier {

    private final WebhookPersistencePort persistence;
    private final WebhookDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${ktoggle.console-url:http://localhost:5173}")
    private String consoleUrl;

    /**
     * @param summary one human-readable line, e.g. "alice published new-checkout (revision #7)"
     * @param path    console path the notification links to (e.g. {@code /features/new-checkout}), or null
     */
    @Transactional
    public void notify(WebhookEvent event, String actor, String summary, String path, Map<String, ?> data) {
        List<Webhook> targets = persistence.findAll().stream().filter(w -> w.subscribedTo(event)).toList();
        enqueue(targets, event, actor, summary, path, data);
    }

    /** "Send test": one delivery to this webhook only, whatever it subscribes to. */
    @Transactional
    public void test(Webhook webhook, String actor) {
        enqueue(List.of(webhook), WebhookEvent.TEST, actor, actor + " sent a test notification to " + webhook.name(),
                "/webhooks", Map.of("webhook", webhook.name()));
    }

    private void enqueue(List<Webhook> targets, WebhookEvent event, String actor, String summary, String path,
                         Map<String, ?> data) {
        if (targets.isEmpty()) {
            return;
        }
        Instant now = Ids.now(clock);
        for (Webhook webhook : targets) {
            UUID id = Ids.newId();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id.toString());
            payload.put("event", event.code());
            payload.put("occurredAt", now.toString());
            payload.put("actor", actor);
            payload.put("summary", summary);
            if (path != null) {
                payload.put("link", consoleUrl.replaceAll("/+$", "") + path);
            }
            payload.set("data", objectMapper.valueToTree(data == null ? Map.of() : data));
            persistence.insertDelivery(new WebhookDelivery(id, webhook.id(), event, payload, WebhookDelivery.Status.PENDING,
                    0, now, null, null, now, null));
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatchSoon();
                }
            });
        }
    }
}
