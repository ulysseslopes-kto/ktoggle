package com.ktogroup.ktoggle.commons.time;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class Ids {

    private static final TimeBasedEpochGenerator UUID_V7 = Generators.timeBasedEpochGenerator();

    private Ids() {
    }

    /** Time-ordered UUIDv7: index-friendly primary keys and event ids. */
    public static UUID newId() {
        return UUID_V7.generate();
    }

    /**
     * Current instant truncated to microseconds — PostgreSQL's TIMESTAMPTZ precision — so any timestamp
     * that participates in a hash reads back from the database exactly as it was hashed.
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
