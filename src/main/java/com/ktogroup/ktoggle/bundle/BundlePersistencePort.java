package com.ktogroup.ktoggle.bundle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BundlePersistencePort {

    Optional<Bundle> findByHash(String hash);

    /** Bundles are content-addressed: inserting an existing hash is a no-op. */
    void insertIfAbsent(Bundle bundle);

    List<Bundle> findByClientKey(String clientKey, int limit);

    /**
     * Serializes publications, rollbacks and unpins until the current transaction ends (cluster-wide), so a
     * publication always compiles the latest committed configuration and activation chains never fork.
     */
    void lockPublication();

    Optional<BundleActivation> findLastActivation(String clientKey);

    /** Latest activation of every connection (what each client key serves now). */
    List<BundleActivation> findCurrentActivations();

    /** The activation in force at {@code instant}: the latest one activated at or before it. */
    Optional<BundleActivation> findActivationAt(String clientKey, Instant instant);

    void insertActivation(BundleActivation activation);

    /** Newest first. */
    List<BundleActivation> findActivations(String clientKey, int limit);

    /** Ascending positions {@code > afterPosition}, at most {@code limit}. Used to walk the chain. */
    List<BundleActivation> findActivationsAfter(String clientKey, long afterPosition, int limit);

    /** Bundles that have no WORM archive copy yet, oldest first. */
    List<Bundle> findNotArchived(int limit);

    void markArchived(String bundleHash, String location, Instant archivedAt);
}
