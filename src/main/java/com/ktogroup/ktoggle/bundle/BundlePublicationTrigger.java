package com.ktogroup.ktoggle.bundle;

import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Two paths lead to publication: immediately after a configuration change commits, and a periodic
 * reconciliation that repairs anything the immediate path missed (pod crash between commit and publish,
 * transient database error). Both are idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundlePublicationTrigger {

    private final BundlePublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfigurationChanged(ConfigurationChangedEvent event) {
        try {
            publisher.publishAll(event.context());
        } catch (RuntimeException e) {
            log.error("Bundle publication after change {} failed; the reconciler will retry", event.context().changeId(), e);
        }
    }

    @Scheduled(fixedDelayString = "${ktoggle.bundle.reconcile-interval:PT1M}", initialDelayString = "PT10S")
    @SchedulerLock(name = "bundle-reconcile", lockAtMostFor = "PT5M")
    public void reconcile() {
        List<BundleActivation> repaired = publisher.publishAll(ChangeContextProvider.system("reconciler"));
        if (!repaired.isEmpty()) {
            log.warn("Reconciler published {} bundle(s) the immediate path missed: {}", repaired.size(),
                    repaired.stream().map(BundleActivation::clientKey).toList());
        }
    }
}
