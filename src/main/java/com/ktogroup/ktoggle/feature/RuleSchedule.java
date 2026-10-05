package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Time window in which a rule is live (GrowthBook "scheduled rules"). SDKs do not evaluate schedules: a rule outside
 * its window is simply left out of the compiled payload, and a new bundle is published when a boundary is crossed —
 * so every bundle still describes exactly what SDKs evaluated, and replay stays faithful.
 *
 * @param startsAt inclusive; null means "already started"
 * @param endsAt   exclusive; null means "never ends"
 */
public record RuleSchedule(Instant startsAt, Instant endsAt) {

    @JsonIgnore
    public boolean isEmpty() {
        return startsAt == null && endsAt == null;
    }

    public boolean activeAt(Instant instant) {
        return (startsAt == null || !instant.isBefore(startsAt)) && (endsAt == null || instant.isBefore(endsAt));
    }

    /** Whether the rule switches on or off in {@code (from, to]}. */
    public boolean crossesBoundary(Instant from, Instant to) {
        return Stream.of(startsAt, endsAt).anyMatch(b -> b != null && b.isAfter(from) && !b.isAfter(to));
    }
}
