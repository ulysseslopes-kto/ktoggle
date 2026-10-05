package com.ktogroup.ktoggle.webhook;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a webhook can subscribe to. The wire name ({@link #code()}) is part of the payload contract. */
public enum WebhookEvent {
    DRAFT_REVIEW_REQUESTED("draft.review_requested", "Review requested"),
    DRAFT_APPROVED("draft.approved", "Draft approved"),
    DRAFT_CHANGES_REQUESTED("draft.changes_requested", "Changes requested"),
    DRAFT_PUBLISHED("draft.published", "Draft published"),
    DRAFT_EMERGENCY_PUBLISHED("draft.emergency_published", "Emergency publication (no approval)"),
    BUNDLE_ROLLED_BACK("bundle.rolled_back", "Rollback"),
    SCHEDULED_RULES_SWITCHED("schedule.switched", "Scheduled rules started or ended"),
    /** Sent only by "Send test"; cannot be subscribed to. */
    TEST("webhook.test", "Test");

    private final String code;
    private final String label;

    WebhookEvent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static WebhookEvent fromCode(String code) {
        for (WebhookEvent event : values()) {
            if (event.code.equals(code) || event.name().equals(code)) {
                return event;
            }
        }
        throw new IllegalArgumentException("Unknown webhook event: " + code);
    }
}
