package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import com.ktogroup.ktoggle.delivery.PayloadEncryption;
import com.ktogroup.ktoggle.delivery.ServedPayload;
import com.ktogroup.ktoggle.growthbook.ShadowComparator.Comparison;
import com.ktogroup.ktoggle.growthbook.ShadowRun.Status;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Shadow mode: periodically checks, client key by client key, that ktoggle would serve exactly what GrowthBook serves,
 * before any consumer is switched. Purely passive: it only reads GrowthBook's SDK payloads; services keep using
 * GrowthBook until they are migrated.
 */
@Slf4j
@Service
public class ShadowService {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final int MAX_ERROR = 500;

    private final GrowthBookProperties properties;
    private final GrowthBookClient client;
    private final ShadowComparator comparator;
    private final ShadowRunPersistencePort persistence;
    private final SdkConnectionService connections;
    private final ActiveBundleRegistry registry;
    private final AttributeService attributes;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, AtomicInteger> divergentGauges = new ConcurrentHashMap<>();

    public ShadowService(GrowthBookProperties properties, GrowthBookClient client, ShadowComparator comparator,
                         ShadowRunPersistencePort persistence, SdkConnectionService connections, ActiveBundleRegistry registry,
                         AttributeService attributes, ObjectMapper objectMapper, MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.comparator = comparator;
        this.persistence = persistence;
        this.connections = connections;
        this.registry = registry;
        this.attributes = attributes;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Per client key: last result, how many clean runs in a row, and whether that is enough to switch consumers.
     *
     * @param ready the last {@code readyAfter} runs all matched
     */
    public record ConnectionStatus(String clientKey, String name, String environmentKey, ShadowRun lastRun, int cleanStreak,
                                   boolean ready, int readyAfter) {
    }

    public List<ConnectionStatus> status() {
        int readyAfter = properties.shadow().readyAfter();
        List<ConnectionStatus> result = new ArrayList<>();
        for (SdkConnection connection : connections.findAll()) {
            List<ShadowRun> runs = persistence.findByClientKey(connection.clientKey(), Math.max(readyAfter, 1));
            int streak = 0;
            for (ShadowRun run : runs) {
                if (run.status() != Status.MATCH) {
                    break;
                }
                streak++;
            }
            result.add(new ConnectionStatus(connection.clientKey(), connection.name(), connection.environmentKey(),
                    runs.isEmpty() ? null : runs.getFirst(), streak, streak >= readyAfter, readyAfter));
        }
        return result;
    }

    public List<ShadowRun> runs(String clientKey, int limit) {
        connections.get(clientKey);
        return persistence.findByClientKey(clientKey, Math.clamp(limit, 1, 100));
    }

    /** Compares every ktoggle client key (or only {@code clientKey}) with GrowthBook now. */
    public List<ShadowRun> runNow(String clientKey, String triggeredBy) {
        if (!properties.configured()) {
            throw com.ktogroup.ktoggle.commons.exception.ValidationException.of(
                    "GrowthBook is not configured (ktoggle.growthbook.api-host): the shadow comparison has nothing to read");
        }
        List<SdkConnection> targets = clientKey == null ? connections.findAll() : List.of(connections.get(clientKey));
        Map<String, String> gbKeys = encryptionKeys();
        return targets.stream().map(c -> compare(c, gbKeys, triggeredBy)).toList();
    }

    @Scheduled(fixedDelayString = "${ktoggle.growthbook.shadow.interval:PT15M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "shadow-comparison", lockAtMostFor = "PT30M")
    public void scheduled() {
        if (!properties.configured() || !properties.shadow().enabled()) {
            return;
        }
        try {
            List<ShadowRun> runs = runNow(null, "system:shadow");
            log.info("Shadow comparison: {}", runs.stream().collect(java.util.stream.Collectors.groupingBy(ShadowRun::status,
                    java.util.stream.Collectors.counting())));
            persistence.deleteBefore(Ids.now(clock).minus(RETENTION));
        } catch (RuntimeException e) {
            log.error("Shadow comparison failed", e);
        }
    }

    private ShadowRun compare(SdkConnection connection, Map<String, String> gbKeys, String triggeredBy) {
        Instant started = Ids.now(clock);
        long t0 = System.nanoTime();
        String key = connection.clientKey();
        Optional<ServedPayload> served = registry.get(key);
        ShadowRun run;
        try {
            Optional<JsonNode> growthbook = client.sdkPayload(key);
            if (growthbook.isEmpty()) {
                run = result(connection, started, t0, Status.NOT_IN_GROWTHBOOK, served, null, null, triggeredBy);
            } else if (served.isEmpty() || served.get().features() == null) {
                run = result(connection, started, t0, Status.NOT_IN_KTOGGLE, served, null, null, triggeredBy);
            } else {
                JsonNode gbPayload = decrypted(growthbook.get(), gbKeys.get(key));
                List<ObjectNode> samples = AttributeSampler.samples(attributes.findAll(),
                        List.of(gbPayload, objectMapper.createObjectNode().set("features", served.get().features())),
                        properties.shadow().samples(), started.toEpochMilli() ^ key.hashCode());
                Comparison comparison = comparator.compare(gbPayload, served.get().features(), samples);
                run = result(connection, started, t0, comparison.clean() ? Status.MATCH : Status.DIVERGENT, served, comparison, null,
                        triggeredBy);
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run = result(connection, started, t0, Status.ERROR, served, null,
                    message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message, triggeredBy);
        }
        persistence.insert(run);
        meterRegistry.counter("ktoggle.shadow.runs", "status", run.status().name()).increment();
        divergentGauges.computeIfAbsent(key, k -> {
            AtomicInteger gauge = new AtomicInteger();
            Gauge.builder("ktoggle.shadow.divergent_features", gauge, AtomicInteger::get).tag("client_key", k).register(meterRegistry);
            return gauge;
        }).set(run.divergentFeatures());
        return run;
    }

    private ShadowRun result(SdkConnection connection, Instant started, long t0, Status status, Optional<ServedPayload> served,
                             Comparison comparison, String error, String triggeredBy) {
        return new ShadowRun(Ids.newId(), connection.clientKey(), started, (System.nanoTime() - t0) / 1_000_000, status,
                served.map(ServedPayload::bundleHash).orElse(null), comparison == null ? 0 : comparison.samples(),
                comparison == null ? 0 : comparison.featuresCompared(), comparison == null ? 0 : comparison.divergences().size(),
                comparison == null ? List.of() : comparison.divergences(), error, triggeredBy);
    }

    /** GrowthBook connections with encryption: the key comes from its REST API (needs the secret key). */
    private JsonNode decrypted(JsonNode payload, String key) {
        if (!payload.hasNonNull("encryptedFeatures")) {
            return payload;
        }
        if (key == null) {
            throw new IllegalStateException("GrowthBook serves this key encrypted; configure ktoggle.growthbook.secret-key so "
                    + "the comparison can read the decryption key");
        }
        try {
            ObjectNode copy = payload.deepCopy();
            copy.set("features", objectMapper.readTree(PayloadEncryption.decrypt(payload.path("encryptedFeatures").asText(), key)
                    .strip()));
            return copy;
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt GrowthBook's payload: " + e.getMessage(), e);
        }
    }

    private Map<String, String> encryptionKeys() {
        Map<String, String> keys = new HashMap<>();
        if (properties.secretKey() == null) {
            return keys;
        }
        try {
            client.list("sdk-connections").forEach(c -> {
                if (c.path("encryptPayload").asBoolean(false)) {
                    keys.put(c.path("key").asText(), c.path("encryptionKey").asText());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not list GrowthBook SDK connections for decryption keys: {}", e.getMessage());
        }
        return keys;
    }
}
