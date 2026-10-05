package com.ktogroup.ktoggle.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseHubTest {

    private final DeliveryRecorder recorder = mock(DeliveryRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SseHub hub = new SseHub(recorder, meters);

    @Test
    void subscribing_sends_the_current_payload_and_records_an_sse_delivery() {
        SseEmitter emitter = hub.subscribe(payload("sdk-a", "h1"), "js/1.0");

        assertThat(emitter.getTimeout()).as("never times out on its own").isZero();
        verify(recorder).record("sdk-a", "h1", DeliveryChannel.SSE, "js/1.0");
        assertThat(connections()).isEqualTo(1);
    }

    @Test
    void broadcast_reaches_every_subscriber_of_the_client_key_only() {
        hub.subscribe(payload("sdk-a", "h1"), "one");
        hub.subscribe(payload("sdk-a", "h1"), "two");
        hub.subscribe(payload("sdk-b", "h9"), "other");

        hub.broadcast(payload("sdk-a", "h2"));

        verify(recorder).record("sdk-a", "h2", DeliveryChannel.SSE, "one");
        verify(recorder).record("sdk-a", "h2", DeliveryChannel.SSE, "two");
        verify(recorder, never()).record(eq("sdk-b"), eq("h2"), any(), any());
    }

    @Test
    void broadcast_to_a_client_key_without_subscribers_is_a_no_op() {
        hub.broadcast(payload("sdk-nobody", "h1"));

        verify(recorder, never()).record(any(), any(), any(), any());
    }

    @Test
    void a_subscriber_whose_connection_is_gone_is_dropped_on_broadcast() {
        SseEmitter gone = hub.subscribe(payload("sdk-a", "h1"), "gone");
        hub.subscribe(payload("sdk-a", "h1"), "alive");
        gone.complete();

        hub.broadcast(payload("sdk-a", "h2"));

        assertThat(connections()).isEqualTo(1);
        verify(recorder, never()).record("sdk-a", "h2", DeliveryChannel.SSE, "gone");
        verify(recorder).record("sdk-a", "h2", DeliveryChannel.SSE, "alive");
        hub.broadcast(payload("sdk-a", "h3"));
        verify(recorder, times(1)).record("sdk-a", "h3", DeliveryChannel.SSE, "alive");
        verify(recorder, never()).record("sdk-a", "h3", DeliveryChannel.SSE, "gone");
    }

    @Test
    void heartbeat_keeps_live_subscribers_and_removes_dead_ones() {
        SseEmitter gone = hub.subscribe(payload("sdk-a", "h1"), "gone");
        hub.subscribe(payload("sdk-a", "h1"), "alive");
        gone.complete();

        hub.heartbeat();

        assertThat(connections()).isEqualTo(1);
        hub.heartbeat();
        assertThat(connections()).as("a removed subscriber is not counted twice").isEqualTo(1);
    }

    private double connections() {
        return meters.get("ktoggle.delivery.sse_connections").gauge().value();
    }

    private static ServedPayload payload(String clientKey, String hash) {
        return new ServedPayload(clientKey, hash, 1, Instant.parse("2026-10-05T12:00:00Z"), "{\"features\":{}}");
    }
}
