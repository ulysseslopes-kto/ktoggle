package com.ktogroup.ktoggle.feature;

import java.util.List;
import java.util.Optional;

public interface FeaturePersistencePort {

    Optional<Feature> findByKey(String key);

    List<Feature> findAll(FeatureFilter filter);

    /** Every non-archived feature, with all environment settings — the input of the payload compiler. */
    List<Feature> findAllActive();

    boolean existsByKey(String key);

    /** Inserts or updates (by key) the feature and its environment settings; returns the persisted state. */
    Feature save(Feature feature);

    void saveRevision(FeatureRevision revision);

    List<FeatureRevision> findRevisions(String featureKey);

    Optional<FeatureRevision> findRevision(String featureKey, int revision);

    record FeatureFilter(String projectKey, String tag, String search, Boolean archived) {
    }
}
