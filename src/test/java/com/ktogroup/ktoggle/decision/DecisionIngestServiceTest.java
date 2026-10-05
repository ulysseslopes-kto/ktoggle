package com.ktogroup.ktoggle.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.decision.DecisionIngestService.DecisionReport;
import com.ktogroup.ktoggle.decision.DecisionIngestService.IngestResult;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import com.ktogroup.ktoggle.delivery.ServedPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionIngestServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final DecisionEventPersistencePort persistence = mock(DecisionEventPersistencePort.class);
    private final ActiveBundleRegistry registry = mock(ActiveBundleRegistry.class);
    private final AttributeService attributeService = mock(AttributeService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<DecisionEvent> stored = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private AttributeDigester digester;
    private DecisionIngestService service;

    @BeforeEach
    void setUp() {
        when(registry.get("sdk-a")).thenReturn(Optional.of(new ServedPayload("sdk-a", HASH, 1, NOW, "plain", "{}")));
        when(attributeService.findAll()).thenReturn(List.of(
                attribute("country", false, false),
                attribute("userId", true, false),
                attribute("legacy", false, true)));
        doAnswer(invocation -> {
            List<DecisionEvent> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            stored.addAll(batch);
            return null;
        }).when(persistence).insertBatch(anyList());
        service = service(10, 2);
    }

    @Test
    void unknown_client_keys_are_rejected_before_looking_at_the_events() {
        when(registry.get("sdk-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest("sdk-x", List.of(report())))
                .isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.UNKNOWN_CLIENT_KEY));
    }

    @Test
    void null_or_empty_batches_are_accepted_as_no_ops() {
        assertThat(service.ingest("sdk-a", null)).isEqualTo(new IngestResult(0, 0, 0, List.of()));
        assertThat(service.ingest("sdk-a", List.of())).isEqualTo(new IngestResult(0, 0, 0, List.of()));
    }

    @Test
    void batches_over_the_per_request_limit_are_rejected_as_a_whole() {
        List<DecisionReport> reports = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reports.add(report());
        }

        assertThatThrownBy(() -> service.ingest("sdk-a", reports)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("At most 5 events per request");
    }

    @Test
    void every_invalid_event_is_reported_with_its_index_and_does_not_stop_the_valid_ones() {
        List<DecisionReport> reports = new ArrayList<>();
        reports.add(report());
        reports.add(null);
        reports.add(report("not-a-hash", "feature", NOW, null));
        reports.add(report(HASH.toUpperCase(), "feature", NOW, null));
        reports.add(report(null, "feature", NOW, null));
        reports.add(report(HASH, " ", NOW, null));

        IngestResult result = service(10, 2, 10).ingest("sdk-a", reports);

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(5);
        assertThat(result.errors()).hasSize(5);
        assertThat(result.errors().get(0)).isEqualTo("events[1]: null event");
        assertThat(result.errors().get(1)).startsWith("events[2]: bundleHash must be");
        assertThat(result.errors().get(4)).isEqualTo("events[5]: featureKey is required");
    }

    @Test
    void feature_keys_longer_than_150_characters_are_rejected() {
        IngestResult result = service.ingest("sdk-a", List.of(report(HASH, "f".repeat(151), NOW, null)));

        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.errors().getFirst()).contains("featureKey is required");
    }

    @Test
    void events_outside_the_accepted_time_window_are_rejected() {
        IngestResult result = service.ingest("sdk-a", List.of(
                report(HASH, "f", NOW.minus(Duration.ofDays(7)).minusSeconds(1), null),
                report(HASH, "f", NOW.plus(Duration.ofMinutes(5)).plusSeconds(1), null),
                report(HASH, "f", null, null),
                report(HASH, "f", NOW.minus(Duration.ofDays(6)), null),
                report(HASH, "f", NOW.plus(Duration.ofMinutes(4)), null)));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(3);
        assertThat(result.errors()).allSatisfy(e -> assertThat(e).contains("occurredAt is missing or outside the accepted window"));
    }

    @Test
    void attributes_must_be_a_json_object() {
        IngestResult result = service.ingest("sdk-a", List.of(
                report(HASH, "f", NOW, JSON.arrayNode()),
                report(HASH, "f", NOW, JSON.textNode("country=BR")),
                report(HASH, "f", NOW, JSON.objectNode()),
                report(HASH, "f", NOW, null)));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.errors()).hasSize(2).allSatisfy(e -> assertThat(e).contains("attributes must be an object"));
    }

    @Test
    void only_the_first_twenty_errors_are_returned_but_all_rejections_are_counted() {
        DecisionIngestService big = service(100, 2, 100);
        List<DecisionReport> reports = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            reports.add(report("bad", "f", NOW, null));
        }

        IngestResult result = big.ingest("sdk-a", reports);

        assertThat(result.rejected()).isEqualTo(30);
        assertThat(result.errors()).hasSize(20);
    }

    @Test
    void events_that_do_not_fit_in_the_bounded_queue_are_dropped_and_counted() {
        DecisionIngestService small = service(2, 2);

        IngestResult result = small.ingest("sdk-a", List.of(report(), report(), report(), report()));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.dropped()).isEqualTo(2);
        assertThat(result.rejected()).isZero();
        assertThat(meters.get("ktoggle.decisions.accepted").counter().count()).isEqualTo(2);
        assertThat(meters.get("ktoggle.decisions.dropped").counter().count()).isEqualTo(2);
    }

    @Test
    void flush_persists_queued_events_in_batches() {
        service.ingest("sdk-a", List.of(report(), report(), report(), report(), report()));

        service.flush();

        assertThat(batchSizes).containsExactly(2, 2, 1);
        assertThat(stored).hasSize(5).allSatisfy(e -> {
            assertThat(e.clientKey()).isEqualTo("sdk-a");
            assertThat(e.receivedAt()).isEqualTo(NOW);
        });
        service.flush();
        verify(persistence, times(3)).insertBatch(anyList());
    }

    @Test
    void a_failing_flush_drops_the_batch_without_losing_the_service() {
        doThrow(new IllegalStateException("db down")).when(persistence).insertBatch(anyList());
        service.ingest("sdk-a", List.of(report(), report(), report()));

        service.flush();

        verify(persistence, times(2)).insertBatch(anyList());
        assertThat(meters.get("ktoggle.decisions.dropped").counter().count()).isEqualTo(3);
        assertThat(stored).isEmpty();
    }

    @Test
    void only_non_pii_non_archived_declared_attributes_are_stored_in_clear_but_the_digest_covers_everything() throws Exception {
        JsonNode attributes = MAPPER.readTree("{\"country\":\"BR\",\"userId\":\"123.456.789-00\",\"legacy\":\"x\",\"undeclared\":1}");

        service.ingest("sdk-a", List.of(report(HASH, "f", NOW, attributes)));
        service.flush();

        DecisionEvent event = stored.getFirst();
        assertThat(event.attributes().properties()).extracting(java.util.Map.Entry::getKey).containsExactly("country");
        assertThat(event.attributesDigest()).isEqualTo(digester.digest(attributes));
        assertThat(event.digestKeyId()).isEqualTo("k1");
        assertThat(digester.matches(attributes, event.attributesDigest(), event.digestKeyId())).isTrue();
    }

    @Test
    void missing_attributes_are_stored_as_an_empty_object() {
        service.ingest("sdk-a", List.of(report()));
        service.flush();

        assertThat(stored.getFirst().attributes()).isEmpty();
        assertThat(stored.getFirst().attributesDigest()).isEqualTo(digester.digest(JSON.objectNode()));
    }

    @Test
    void the_allowlist_is_cached_for_a_minute() {
        service.ingest("sdk-a", List.of(report()));
        service.ingest("sdk-a", List.of(report()));

        verify(attributeService, times(1)).findAll();
    }

    @Test
    void the_allowlist_is_refreshed_after_its_ttl() {
        MutableClock clock = new MutableClock(NOW);
        DecisionIngestService timed = service(10, 2, 5, clock);
        timed.ingest("sdk-a", List.of(report()));
        clock.advance(Duration.ofSeconds(61));
        timed.ingest("sdk-a", List.of(report()));

        verify(attributeService, times(2)).findAll();
    }

    @Test
    void if_the_allowlist_cannot_be_loaded_nothing_is_stored_in_clear() throws Exception {
        when(attributeService.findAll()).thenThrow(new IllegalStateException("db down"));

        service.ingest("sdk-a", List.of(report(HASH, "f", NOW, MAPPER.readTree("{\"country\":\"BR\"}"))));
        service.flush();

        assertThat(stored.getFirst().attributes()).isEmpty();
    }

    @Test
    void if_a_refresh_fails_the_previous_allowlist_is_kept() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        DecisionIngestService timed = service(10, 2, 5, clock);
        timed.ingest("sdk-a", List.of(report()));
        when(attributeService.findAll()).thenThrow(new IllegalStateException("db down"));
        clock.advance(Duration.ofMinutes(2));

        timed.ingest("sdk-a", List.of(report(HASH, "f", NOW, MAPPER.readTree("{\"country\":\"BR\"}"))));
        timed.flush();

        assertThat(stored.getLast().attributes().path("country").asText()).isEqualTo("BR");
    }

    @Test
    void client_supplied_event_ids_are_kept_and_missing_ones_generated_and_long_labels_truncated() {
        UUID eventId = UUID.randomUUID();
        DecisionReport withId = new DecisionReport(eventId, HASH, "f", JSON.booleanNode(true), "r".repeat(150), "s".repeat(150),
                "k".repeat(150), NOW, null);

        service.ingest("sdk-a", List.of(withId, report()));
        service.flush();

        assertThat(stored.get(0).eventId()).isEqualTo(eventId);
        assertThat(stored.get(0).ruleId()).hasSize(100);
        assertThat(stored.get(0).source()).hasSize(100);
        assertThat(stored.get(0).sdk()).hasSize(100);
        assertThat(stored.get(1).eventId()).isNotNull().isNotEqualTo(eventId);
        assertThat(stored.get(1).ruleId()).isEqualTo("fr_1");
    }

    @Test
    void shutdown_drains_what_is_still_queued() {
        service.ingest("sdk-a", List.of(report()));

        service.drainOnShutdown();

        assertThat(stored).hasSize(1);
    }

    private DecisionIngestService service(int queueCapacity, int batchSize) {
        return service(queueCapacity, batchSize, 5);
    }

    private DecisionIngestService service(int queueCapacity, int batchSize, int maxEventsPerRequest) {
        return service(queueCapacity, batchSize, maxEventsPerRequest, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DecisionIngestService service(int queueCapacity, int batchSize, int maxEventsPerRequest, Clock clock) {
        DecisionProperties properties = new DecisionProperties("k1", Base64.getEncoder().encodeToString(new byte[32]), queueCapacity,
                batchSize, null, null, maxEventsPerRequest, 0);
        digester = new AttributeDigester(new CanonicalJson(MAPPER), properties);
        return new DecisionIngestService(persistence, registry, attributeService, digester, properties, clock, meters);
    }

    private static DecisionReport report() {
        return report(HASH, "checkout", NOW, null);
    }

    private static DecisionReport report(String bundleHash, String featureKey, Instant occurredAt, JsonNode attributes) {
        return new DecisionReport(null, bundleHash, featureKey, JSON.booleanNode(true), "fr_1", "force", "okhttp", occurredAt, attributes);
    }

    private static Attribute attribute(String key, boolean pii, boolean archived) {
        return new Attribute(key, AttributeDatatype.STRING, null, false, pii, List.of(), archived, NOW, NOW, 0L);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
