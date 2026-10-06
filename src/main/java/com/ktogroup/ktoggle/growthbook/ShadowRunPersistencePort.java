package com.ktogroup.ktoggle.growthbook;

import java.time.Instant;
import java.util.List;

public interface ShadowRunPersistencePort {

    void insert(ShadowRun run);

    /** Most recent first. */
    List<ShadowRun> findByClientKey(String clientKey, int limit);

    int deleteBefore(Instant before);
}
