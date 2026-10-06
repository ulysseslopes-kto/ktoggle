package com.ktogroup.ktoggle.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Opt-in decision logging. Consumers wire their SDK's feature-usage callback to {@code POST /api/decisions/{clientKey}}.
 *
 * <p>LGPD by design: attributes not explicitly declared as non-PII are never stored in clear; the full set is kept
 * only as an HMAC digest. Ingestion is asynchronous with a bounded buffer: under overload, events are dropped
 * (counted in {@code ktoggle.decisions.dropped}) rather than slowing SDK callers down.
 */
@Slf4j
@Service
public class DecisionIngestService {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_KEY_LENGTH = 150;
    private static final Duration ALLOWLIST_TTL = Duration.ofMinutes(1);
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final DecisionEventPersistencePort persistence;
    private final ActiveBundleRegistry registry;
    private final AttributeService attributeService;
    private final AttributeDigester digester;
    private final DecisionProperties properties;
    private final Clock clock;
    private final BlockingQueue<DecisionEvent> queue;
    private final Counter accepted;
    private final Counter rejected;
    private final Counter dropped;
    private volatile Allowlist allowlist = new Allowlist(Set.of(), Instant.EPOCH);

    public DecisionIngestService(DecisionEventPersistencePort persistence, ActiveBundleRegistry registry,
                                 AttributeService attributeService, AttributeDigester digester, DecisionProperties properties,
                                 Clock clock, MeterRegistry meterRegistry) {
        this.persistence = persistence;
        this.registry = registry;
        this.attributeService = attributeService;
        this.digester = digester;
        this.properties = properties;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.accepted = meterRegistry.counter("ktoggle.decisions.accepted");
        this.rejected = meterRegistry.counter("ktoggle.decisions.rejected");
        this.dropped = meterRegistry.counter("ktoggle.decisions.dropped");
        meterRegistry.gauge("ktoggle.decisions.queue_size", queue, BlockingQueue::size);
    }

    public IngestResult ingest(String clientKey, List<DecisionReport> reports) {
        if (registry.get(clientKey).isEmpty()) {
            throw new NotFoundException(MessageCode.UNKNOWN_CLIENT_KEY, "Unknown client key");
        }
        if (reports == null || reports.isEmpty()) {
            return new IngestResult(0, 0, 0, List.of());
        }
        if (reports.size() > properties.maxEventsPerRequest()) {
            throw ValidationException.of("At most %d events per request".formatted(properties.maxEventsPerRequest()));
        }
        Instant now = clock.instant();
        int ok = 0;
        int dropCount = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            String error = validate(reports.get(i), now);
            if (error != null) {
                if (errors.size() < 20) {
                    errors.add("events[%d]: %s".formatted(i, error));
                }
                continue;
            }
            if (queue.offer(toEvent(clientKey, reports.get(i), now))) {
                ok++;
            } else {
                dropCount++;
            }
        }
        int bad = reports.size() - ok - dropCount;
        accepted.increment(ok);
        rejected.increment(bad);
        dropped.increment(dropCount);
        return new IngestResult(ok, bad, dropCount, errors);
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<DecisionEvent> batch = new ArrayList<>(properties.batchSize());
        while (queue.drainTo(batch, properties.batchSize()) > 0) {
            try {
                persistence.insertBatch(batch);
            } catch (RuntimeException e) {
                if (batch.size() == 1) {
                    dropped.increment();
                    log.error("Failed to persist a decision event (dropped)", e);
                } else {
                    log.warn("Failed to persist {} decision events as a batch, retrying one by one: {}", batch.size(), e.getMessage());
                    insertOneByOne(batch);
                }
            }
            batch.clear();
        }
    }

    /**
     * One bad event must not cost the whole batch. Gives up after {@link #MAX_CONSECUTIVE_FAILURES} failures in a row:
     * the database itself is then the problem, and retrying every event would only stall the flush.
     */
    private void insertOneByOne(List<DecisionEvent> batch) {
        int failed = 0;
        int consecutive = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
                failed += batch.size() - i;
                break;
            }
            try {
                persistence.insertBatch(List.of(batch.get(i)));
                consecutive = 0;
            } catch (RuntimeException e) {
                failed++;
                consecutive++;
                log.debug("Decision event {} rejected: {}", batch.get(i).eventId(), e.getMessage());
            }
        }
        if (failed > 0) {
            dropped.increment(failed);
            log.error("Failed to persist {} of {} decision events (dropped)", failed, batch.size());
        }
    }

    @PreDestroy
    void drainOnShutdown() {
        flush();
    }

    /** Attributes declared as non-PII, cached for {@link #ALLOWLIST_TTL}. Fails closed (keeps nothing) if never loaded. */
    private Set<String> nonPiiAttributes() {
        Allowlist current = allowlist;
        if (current.loadedAt().plus(ALLOWLIST_TTL).isAfter(clock.instant())) {
            return current.keys();
        }
        try {
            Set<String> keys = attributeService.findAll().stream()
                    .filter(attribute -> !attribute.pii() && !attribute.archived())
                    .map(Attribute::key)
                    .collect(Collectors.toUnmodifiableSet());
            allowlist = new Allowlist(keys, clock.instant());
            return keys;
        } catch (RuntimeException e) {
            log.warn("Could not refresh the non-PII attribute allowlist, keeping the previous one", e);
            return current.keys();
        }
    }

    private record Allowlist(Set<String> keys, Instant loadedAt) {
    }

    private String validate(DecisionReport report, Instant now) {
        if (report == null) {
            return "null event";
        }
        if (report.bundleHash() == null || !HASH.matcher(report.bundleHash()).matches()) {
            return "bundleHash must be the 64-char hex hash served by ktoggle";
        }
        if (report.featureKey() == null || report.featureKey().isBlank() || report.featureKey().length() > MAX_KEY_LENGTH) {
            return "featureKey is required";
        }
        if (report.occurredAt() == null
                || report.occurredAt().isBefore(now.minus(properties.maxPastAge()))
                || report.occurredAt().isAfter(now.plus(properties.maxFutureSkew()))) {
            return "occurredAt is missing or outside the accepted window";
        }
        if (report.attributes() != null && !report.attributes().isObject()) {
            return "attributes must be an object";
        }
        return null;
    }

    private DecisionEvent toEvent(String clientKey, DecisionReport report, Instant now) {
        JsonNode attributes = report.attributes() == null ? JsonNodeFactory.instance.objectNode() : report.attributes();
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        Set<String> allowed = nonPiiAttributes();
        for (Map.Entry<String, JsonNode> field : attributes.properties()) {
            if (allowed.contains(field.getKey())) {
                kept.set(field.getKey(), field.getValue());
            }
        }
        UUID eventId = report.eventId() == null ? Ids.newId() : report.eventId();
        return new DecisionEvent(eventId, clientKey, report.bundleHash(), report.featureKey(), report.value(),
                truncate(report.ruleId()), truncate(report.source()), truncate(report.sdk()), report.occurredAt(),
                now, kept, digester.digest(attributes), digester.keyId());
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 100 ? value : value.substring(0, 100);
    }

    /**
     * @param eventId optional but recommended (UUID generated by the client): makes retries idempotent
     */
    public record DecisionReport(UUID eventId, String bundleHash, String featureKey, JsonNode value, String ruleId,
                                 String source, String sdk, Instant occurredAt, JsonNode attributes) {
    }

    public record IngestResult(int accepted, int rejected, int dropped, List<String> errors) {
    }
}
