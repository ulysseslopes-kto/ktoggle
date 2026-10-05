package com.ktogroup.ktoggle.delivery;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Answers "which configuration did client X actually receive, and when?" without changing any consumer and
 * without storing IP addresses (LGPD): deliveries are aggregated in memory per (client key, bundle, channel,
 * SDK hint, hour) and flushed periodically as upserts.
 */
@Slf4j
@Component
public class DeliveryRecorder {

    private static final int MAX_SDK_HINT = 100;

    private final DeliveryLogPersistencePort persistence;
    private final Clock clock;
    private final String pod;
    private final Map<Key, Aggregate> pending = new ConcurrentHashMap<>();

    public DeliveryRecorder(DeliveryLogPersistencePort persistence, Clock clock,
                            @Value("${HOSTNAME:local}") String pod) {
        this.persistence = persistence;
        this.clock = clock;
        this.pod = pod;
    }

    public void record(String clientKey, String bundleHash, DeliveryChannel channel, String sdkHint) {
        Instant now = clock.instant();
        Key key = new Key(clientKey, bundleHash, channel, sdkHint(sdkHint), now.truncatedTo(ChronoUnit.HOURS));
        pending.compute(key, (k, existing) -> existing == null ? new Aggregate(now, now, 1) : existing.add(now));
    }

    @Scheduled(fixedDelayString = "${ktoggle.delivery.log-flush-interval:PT1M}")
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<DeliveryLogEntry> batch = new ArrayList<>();
        for (Key key : List.copyOf(pending.keySet())) {
            Aggregate aggregate = pending.remove(key);
            if (aggregate != null) {
                batch.add(new DeliveryLogEntry(key.clientKey(), key.bundleHash(), key.channel(), pod, key.window(),
                        aggregate.first(), aggregate.last(), aggregate.count(), key.sdkHint()));
            }
        }
        try {
            persistence.upsert(batch);
        } catch (RuntimeException e) {
            log.warn("Could not flush {} delivery log aggregates (dropped)", batch.size(), e);
        }
    }

    /** First product token of the User-Agent (e.g. {@code okhttp/4.11.0}); never the full string. */
    static String sdkHint(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String token = userAgent.strip().split("\\s+")[0];
        return token.length() > MAX_SDK_HINT ? token.substring(0, MAX_SDK_HINT) : token;
    }

    private record Key(String clientKey, String bundleHash, DeliveryChannel channel, String sdkHint, Instant window) {
    }

    private record Aggregate(Instant first, Instant last, long count) {
        Aggregate add(Instant at) {
            return new Aggregate(first, at.isAfter(last) ? at : last, count + 1);
        }
    }
}
