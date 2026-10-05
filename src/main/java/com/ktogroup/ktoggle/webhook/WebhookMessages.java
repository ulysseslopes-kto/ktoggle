package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Renders the body actually sent, from the generic payload stored in the outbox. */
public final class WebhookMessages {

    private WebhookMessages() {
    }

    /**
     * {@code GENERIC}: the payload as is ({@code id, event, occurredAt, actor, summary, link, data}).
     * {@code SLACK}: an incoming-webhook message ({@code {"text": ...}}) with the summary and a link to the console.
     */
    public static JsonNode body(Webhook.Format format, JsonNode payload) {
        if (format == Webhook.Format.GENERIC) {
            return payload;
        }
        StringBuilder text = new StringBuilder("*ktoggle* · ").append(escape(payload.path("summary").asText()));
        String reason = payload.path("data").path("reason").asText("");
        if (!reason.isBlank()) {
            text.append("\n>").append(escape(reason));
        }
        String link = payload.path("link").asText("");
        if (!link.isBlank()) {
            text.append("\n<").append(link).append("|Open in ktoggle>");
        }
        ObjectNode slack = JsonNodeFactory.instance.objectNode();
        slack.put("text", text.toString());
        return slack;
    }

    /** Slack mrkdwn control characters. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
