package com.ktogroup.ktoggle.draft;

import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import java.time.Instant;
import java.util.UUID;
import lombok.With;

/**
 * A staged change to a feature. {@code proposed} is the full desired state; {@code baseRevision} is the live
 * revision it was started from, used for the three-way merge when someone else published in the meantime.
 */
@With
public record FeatureDraft(
        UUID id,
        String featureKey,
        String title,
        int baseRevision,
        DraftStatus status,
        FeatureSnapshot proposed,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        Integer publishedRevision,
        Long version) {
}
