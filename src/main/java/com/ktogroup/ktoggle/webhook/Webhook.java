package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.With;

/**
 * A subscriber endpoint. {@code secret} signs deliveries; it is returned only when the webhook is created.
 *
 * @param format {@code GENERIC}: the ktoggle JSON payload; {@code SLACK}: a Slack incoming-webhook message
 */
@With
public record Webhook(UUID id, String name, String url, Format format, List<WebhookEvent> events, @JsonIgnore String secret,
                      boolean enabled, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt, long version) {

    public Webhook {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public boolean subscribedTo(WebhookEvent event) {
        return enabled && events.contains(event);
    }

    public enum Format {
        GENERIC,
        SLACK
    }
}
