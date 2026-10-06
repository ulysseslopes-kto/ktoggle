package com.ktogroup.ktoggle.growthbook;

import com.ktogroup.ktoggle.growthbook.ShadowComparator.Divergence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One shadow comparison of a client key.
 *
 * @param bundleHash the ktoggle bundle that was compared (null when ktoggle serves nothing for the key)
 */
public record ShadowRun(UUID id, String clientKey, Instant startedAt, long durationMs, Status status, String bundleHash,
                        int samples, int featuresCompared, int divergentFeatures, List<Divergence> divergences, String error,
                        String triggeredBy) {

    public enum Status {
        /** Both sides give every simulated user the same values. */
        MATCH,
        /** At least one feature differs. */
        DIVERGENT,
        /** GrowthBook does not know this client key (nothing to compare). */
        NOT_IN_GROWTHBOOK,
        /** ktoggle has not published anything for this client key yet. */
        NOT_IN_KTOGGLE,
        /** GrowthBook could not be read or the comparison failed. */
        ERROR
    }
}
