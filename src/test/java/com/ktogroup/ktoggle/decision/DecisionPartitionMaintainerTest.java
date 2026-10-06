package com.ktogroup.ktoggle.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DecisionPartitionMaintainerTest {

    private final DecisionEventPersistencePort persistence = mock(DecisionEventPersistencePort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-30T23:30:00Z"), ZoneOffset.UTC);
    private final DecisionProperties properties = new DecisionProperties(null, null, 0, 0, null, null, 0, 6);
    private final DecisionPartitionMaintainer maintainer = new DecisionPartitionMaintainer(persistence, properties, clock);

    @Test
    void startup_creates_the_previous_month_the_current_one_and_the_next_two_without_dropping_anything() {
        maintainer.onStartup();

        ArgumentCaptor<YearMonth> months = ArgumentCaptor.forClass(YearMonth.class);
        verify(persistence, times(4)).ensureMonthlyPartition(months.capture());
        assertThat(months.getAllValues()).as("late events (up to maxPastAge) may belong to the previous month")
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
        verify(persistence, never()).dropPartitionsBefore(any());
    }

    @Test
    void a_month_that_cannot_be_created_does_not_prevent_the_others() {
        doThrow(new IllegalStateException("default partition holds rows of that month"))
                .when(persistence).ensureMonthlyPartition(YearMonth.of(2026, 10));

        maintainer.onStartup();

        verify(persistence).ensureMonthlyPartition(YearMonth.of(2027, 1));
    }

    @Test
    void nightly_maintenance_creates_partitions_then_drops_those_past_retention() {
        when(persistence.dropPartitionsBefore(YearMonth.of(2026, 5))).thenReturn(List.of("decision_event_2026_04"));

        maintainer.maintain();

        InOrder order = inOrder(persistence);
        order.verify(persistence).ensureMonthlyPartition(YearMonth.of(2026, 11));
        order.verify(persistence).ensureMonthlyPartition(YearMonth.of(2027, 1));
        order.verify(persistence).dropPartitionsBefore(YearMonth.of(2026, 5));
    }

    @Test
    void nothing_to_drop_is_not_an_error() {
        when(persistence.dropPartitionsBefore(any())).thenReturn(List.of());

        maintainer.maintain();

        verify(persistence).dropPartitionsBefore(YearMonth.of(2026, 5));
    }
}
