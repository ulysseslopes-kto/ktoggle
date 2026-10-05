package com.ktogroup.ktoggle.feature;

import java.time.Instant;
import java.util.UUID;

/** Immutable (append-only at the database level) snapshot of a feature after each change. */
public record FeatureRevision(String featureKey, int revision, FeatureSnapshot snapshot, UUID changeId, String comment,
                              String createdBy, Instant createdAt) {
}
