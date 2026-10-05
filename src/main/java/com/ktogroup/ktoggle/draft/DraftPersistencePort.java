package com.ktogroup.ktoggle.draft;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DraftPersistencePort {

    Optional<FeatureDraft> findById(UUID id);

    /** Drafts of a feature in the given statuses, newest first. */
    List<FeatureDraft> findByFeature(String featureKey, Collection<DraftStatus> statuses);

    /** All drafts in the given statuses (e.g. the review queue), newest first. */
    List<FeatureDraft> findByStatus(Collection<DraftStatus> statuses, int limit);

    /** Inserts (version null) or updates with optimistic locking; returns the persisted state. */
    FeatureDraft save(FeatureDraft draft);

    void insertEvent(DraftEvent event);

    List<DraftEvent> events(UUID draftId);

    ReviewSettings reviewSettings();

    ReviewSettings saveReviewSettings(ReviewSettings settings);
}
