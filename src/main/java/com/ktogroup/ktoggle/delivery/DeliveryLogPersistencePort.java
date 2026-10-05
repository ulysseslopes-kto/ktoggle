package com.ktogroup.ktoggle.delivery;

import java.time.Instant;
import java.util.List;

public interface DeliveryLogPersistencePort {

    /** Merges aggregates into existing rows of the same (client, bundle, channel, pod, window). */
    void upsert(List<DeliveryLogEntry> entries);

    /** Newest first, optionally bounded in time. */
    List<DeliveryLogEntry> find(String clientKey, Instant from, Instant to, int limit);
}
