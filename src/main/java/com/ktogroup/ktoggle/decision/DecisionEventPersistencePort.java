package com.ktogroup.ktoggle.decision;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionEventPersistencePort {

    /** Idempotent: an event already stored (same id and occurrence time) is ignored. */
    void insertBatch(List<DecisionEvent> events);

    List<DecisionEvent> search(DecisionQuery query);

    Optional<DecisionEvent> findById(UUID eventId);

    void ensureMonthlyPartition(YearMonth month);

    /** Drops monthly partitions entirely older than {@code month}; returns their names. */
    List<String> dropPartitionsBefore(YearMonth month);

    record DecisionQuery(String clientKey, String featureKey, String bundleHash, Instant from, Instant to, int limit) {
    }
}
