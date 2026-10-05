package com.ktogroup.ktoggle.draft;

import java.time.Instant;
import java.util.UUID;

/** One entry of a draft's append-only lifecycle/conversation log. */
public record DraftEvent(UUID id, UUID draftId, Type type, String actor, String comment, Instant occurredAt) {

    public enum Type {
        CREATED,
        UPDATED,
        REVIEW_REQUESTED,
        APPROVED,
        CHANGES_REQUESTED,
        REVIEW_RESET,
        COMMENTED,
        REBASED,
        PUBLISHED,
        BYPASS_PUBLISHED,
        DISCARDED
    }
}
