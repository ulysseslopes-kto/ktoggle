package com.ktogroup.ktoggle.bundle;

import com.ktogroup.ktoggle.commons.time.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copies every bundle to the WORM archive. Bundles are verified before being archived, so the archive never
 * receives tampered content. Runs on one pod at a time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ktoggle.bundle.archive.s3.enabled", havingValue = "true")
public class BundleArchiver {

    private static final int BATCH = 100;

    private final BundlePersistencePort persistence;
    private final BundleArchive archive;
    private final BundleCodec codec;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @SchedulerLock(name = "bundle-archiver", lockAtMostFor = "PT15M")
    public void archivePending() {
        List<Bundle> pending = persistence.findNotArchived(BATCH);
        for (Bundle bundle : pending) {
            try {
                codec.verify(bundle);
                String location = archive.store(bundle);
                persistence.markArchived(bundle.hash(), location, Ids.now(clock));
                meterRegistry.counter("ktoggle.bundle.archived").increment();
            } catch (RuntimeException e) {
                meterRegistry.counter("ktoggle.bundle.archive_failures").increment();
                log.error("Could not archive bundle {}", bundle.hash(), e);
            }
        }
    }
}
