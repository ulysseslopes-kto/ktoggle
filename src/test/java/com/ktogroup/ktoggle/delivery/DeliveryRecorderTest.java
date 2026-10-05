package com.ktogroup.ktoggle.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliveryRecorderTest {

    private final DeliveryLogPersistencePort persistence = mock(DeliveryLogPersistencePort.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T12:10:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final DeliveryRecorder recorder = new DeliveryRecorder(persistence, clock, "pod-1");

    @Test
    void deliveries_of_the_same_client_bundle_channel_hint_and_hour_are_aggregated() {
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, "okhttp/4.11.0 (Android)");
        now.set(Instant.parse("2026-10-05T12:40:00Z"));
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, "okhttp/4.11.0 (Android)");
        recorder.record("sdk-a", "h1", DeliveryChannel.SSE, "okhttp/4.11.0 (Android)");

        recorder.flush();

        List<DeliveryLogEntry> entries = flushed();
        assertThat(entries).hasSize(2);
        DeliveryLogEntry poll = entries.stream().filter(e -> e.channel() == DeliveryChannel.POLL).findFirst().orElseThrow();
        assertThat(poll.deliveries()).isEqualTo(2);
        assertThat(poll.pod()).isEqualTo("pod-1");
        assertThat(poll.sdkHint()).isEqualTo("okhttp/4.11.0");
        assertThat(poll.windowStart()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(poll.firstSeen()).isEqualTo(Instant.parse("2026-10-05T12:10:00Z"));
        assertThat(poll.lastSeen()).isEqualTo(Instant.parse("2026-10-05T12:40:00Z"));
    }

    @Test
    void deliveries_in_different_hours_or_for_different_bundles_stay_separate() {
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);
        now.set(Instant.parse("2026-10-05T13:01:00Z"));
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);
        recorder.record("sdk-a", "h2", DeliveryChannel.POLL, null);

        recorder.flush();

        assertThat(flushed()).hasSize(3).allSatisfy(e -> assertThat(e.deliveries()).isEqualTo(1));
    }

    @Test
    void a_late_older_delivery_does_not_move_last_seen_backwards() {
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);
        now.set(Instant.parse("2026-10-05T12:05:00Z"));
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);

        recorder.flush();

        assertThat(flushed().getFirst().lastSeen()).isEqualTo(Instant.parse("2026-10-05T12:10:00Z"));
    }

    @Test
    void flush_without_pending_deliveries_does_not_touch_the_database() {
        recorder.flush();

        verify(persistence, never()).upsert(any());
    }

    @Test
    void flushed_aggregates_are_not_flushed_twice() {
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);
        recorder.flush();
        recorder.flush();

        verify(persistence, times(1)).upsert(any());
    }

    @Test
    void a_failing_flush_is_swallowed_and_the_batch_is_dropped() {
        doThrow(new IllegalStateException("db down")).when(persistence).upsert(any());
        recorder.record("sdk-a", "h1", DeliveryChannel.POLL, null);

        recorder.flush();
        recorder.flush();

        verify(persistence, times(1)).upsert(any());
    }

    @Test
    void sdk_hint_is_the_first_user_agent_token_only() {
        assertThat(DeliveryRecorder.sdkHint("  growthbook-java/0.10 (Linux x86_64) ")).isEqualTo("growthbook-java/0.10");
    }

    @Test
    void sdk_hint_is_absent_for_missing_or_blank_user_agents() {
        assertThat(DeliveryRecorder.sdkHint(null)).isNull();
        assertThat(DeliveryRecorder.sdkHint("   ")).isNull();
    }

    @Test
    void sdk_hint_is_truncated_to_one_hundred_characters() {
        assertThat(DeliveryRecorder.sdkHint("x".repeat(250) + " rest")).hasSize(100);
    }

    private List<DeliveryLogEntry> flushed() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeliveryLogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistence).upsert(captor.capture());
        return captor.getValue();
    }
}
