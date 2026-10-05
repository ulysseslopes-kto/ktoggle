package com.ktogroup.ktoggle.bundle;

import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.webhook.WebhookEvent;
import com.ktogroup.ktoggle.webhook.WebhookNotifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes new bundles shortly after a scheduled rule starts or ends. The compiled payload depends on the instant
 * it is compiled for, so all this job has to do is notice that a boundary was crossed and republish; the activation
 * is attributed to {@code system:scheduler} with the rules that switched as the reason.
 *
 * <p>Stateless: it looks back over a window longer than its interval, and publication is idempotent, so a boundary
 * seen twice publishes once. If the job is down for longer than the look-back, the reconciler still converges.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleScheduleWatcher {

    static final Duration LOOK_BACK = Duration.ofSeconds(60);
    private static final int MAX_REASON_ITEMS = 10;

    private final FeaturePersistencePort featurePersistence;
    private final BundlePublisher publisher;
    private final WebhookNotifier webhooks;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${ktoggle.bundle.schedule-check-interval:PT10S}", initialDelayString = "PT5S")
    @SchedulerLock(name = "rule-schedules", lockAtMostFor = "PT2M")
    public void publishDueSchedules() {
        Instant now = Ids.now(clock);
        List<String> switched = switchedBetween(featurePersistence.findAllActive(), now.minus(LOOK_BACK), now);
        if (switched.isEmpty()) {
            return;
        }
        List<BundleActivation> published = publisher.publishAll(new ChangeContext(Ids.newId(), "system:scheduler", reason(switched)));
        if (!published.isEmpty()) {
            log.info("Scheduled rules switched ({}); published {} bundle(s)", switched, published.size());
            webhooks.notify(WebhookEvent.SCHEDULED_RULES_SWITCHED, "system:scheduler",
                    "Scheduled rules switched: " + String.join(", ", switched), "/features",
                    Map.of("switched", switched, "bundles", published.size()));
        }
    }

    /** "feature/env/ruleId started|ended" for every enabled rule whose schedule has a boundary in {@code (from, to]}. */
    static List<String> switchedBetween(List<Feature> features, Instant from, Instant to) {
        List<String> switched = new ArrayList<>();
        for (Feature feature : features) {
            for (Map.Entry<String, EnvironmentSettings> environment : feature.environments().entrySet()) {
                if (!environment.getValue().enabled()) {
                    continue;
                }
                for (Rule rule : environment.getValue().rules()) {
                    if (!rule.enabled() || rule.schedule() == null || !rule.schedule().crossesBoundary(from, to)) {
                        continue;
                    }
                    switched.add("%s/%s/%s %s".formatted(feature.key(), environment.getKey(), rule.id(),
                            rule.schedule().activeAt(to) ? "started" : "ended"));
                }
            }
        }
        return switched;
    }

    private static String reason(List<String> switched) {
        String items = String.join(", ", switched.subList(0, Math.min(switched.size(), MAX_REASON_ITEMS)));
        return "Scheduled rule window: " + items + (switched.size() > MAX_REASON_ITEMS ? " and %d more".formatted(
                switched.size() - MAX_REASON_ITEMS) : "");
    }
}
