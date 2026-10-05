package com.ktogroup.ktoggle.decision;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly partitions of {@code decision_event} ahead of time (current month + 2) and drops the ones older
 * than the retention period. Dropping whole partitions is the cheapest way to enforce retention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionPartitionMaintainer {

    private static final int MONTHS_AHEAD = 2;

    private final DecisionEventPersistencePort persistence;
    private final DecisionProperties properties;
    private final Clock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensurePartitions();
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    @SchedulerLock(name = "decision-partitions", lockAtMostFor = "PT10M")
    public void maintain() {
        ensurePartitions();
        YearMonth oldestKept = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(properties.retentionMonths());
        List<String> dropped = persistence.dropPartitionsBefore(oldestKept);
        if (!dropped.isEmpty()) {
            log.info("Dropped decision partitions past retention ({} months): {}", properties.retentionMonths(), dropped);
        }
    }

    private void ensurePartitions() {
        YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            persistence.ensureMonthlyPartition(current.plusMonths(i));
        }
    }
}
