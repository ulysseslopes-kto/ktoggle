package com.ktogroup.ktoggle.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class WebhookMessagesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generic_format_sends_the_payload_as_is() {
        ObjectNode payload = payload();
        assertThat(WebhookMessages.body(Webhook.Format.GENERIC, payload)).isSameAs(payload);
    }

    @Test
    void slack_format_builds_a_message_with_the_reason_and_a_link() {
        String text = WebhookMessages.body(Webhook.Format.SLACK, payload()).path("text").asText();
        assertThat(text).startsWith("*ktoggle* · alice published new-checkout &lt;b&gt;")
                .contains("\n>incident INC-42")
                .endsWith("<http://ktoggle/features/new-checkout|Open in ktoggle>");
    }

    @Test
    void signatures_verify_only_with_the_same_secret_timestamp_and_body() {
        String signature = WebhookSignature.sign("whsec_1", 1_700_000_000L, "{\"a\":1}");
        assertThat(signature).startsWith("sha256=").hasSize(7 + 64);
        assertThat(WebhookSignature.verify("whsec_1", 1_700_000_000L, "{\"a\":1}", signature)).isTrue();
        assertThat(WebhookSignature.verify("whsec_2", 1_700_000_000L, "{\"a\":1}", signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_1", 1_700_000_001L, "{\"a\":1}", signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_1", 1_700_000_000L, "{\"a\":2}", signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_1", 1_700_000_000L, "{\"a\":1}", null)).isFalse();
    }

    private ObjectNode payload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("event", "draft.published");
        payload.put("summary", "alice published new-checkout <b>");
        payload.put("link", "http://ktoggle/features/new-checkout");
        payload.putObject("data").put("reason", "incident INC-42");
        return payload;
    }
}
