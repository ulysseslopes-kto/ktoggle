package com.ktogroup.ktoggle.draft;

public enum DraftStatus {
    DRAFT,
    PENDING_REVIEW,
    CHANGES_REQUESTED,
    APPROVED,
    PUBLISHED,
    DISCARDED;

    public boolean isOpen() {
        return this != PUBLISHED && this != DISCARDED;
    }
}
