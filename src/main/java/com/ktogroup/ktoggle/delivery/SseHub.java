package com.ktogroup.ktoggle.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events compatible with the GrowthBook streaming protocol: a {@code features} event whose data is the
 * full payload, sent on connect and on every activation. Comments are sent periodically as heartbeat so idle
 * proxies/load balancers do not close the stream.
 */
@Slf4j
@Component
public class SseHub {

    static final String FEATURES_EVENT = "features";
    /** Remote-eval SDKs re-post /api/eval when they get this (the payload itself is never streamed to them). */
    static final String FEATURES_UPDATED_EVENT = "features-updated";

    private final Map<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final DeliveryRecorder deliveryRecorder;

    public SseHub(DeliveryRecorder deliveryRecorder, MeterRegistry meterRegistry) {
        this.deliveryRecorder = deliveryRecorder;
        Gauge.builder("ktoggle.delivery.sse_connections", connections, AtomicInteger::get).register(meterRegistry);
    }

    public SseEmitter subscribe(ServedPayload current, String sdkHint) {
        Subscriber subscriber = register(current.clientKey(), sdkHint);
        sendInitial(subscriber, current);
        return subscriber.emitter;
    }

    /** Registered before the initial send, so no activation in between is missed. */
    Subscriber register(String clientKey, String sdkHint) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(clientKey, emitter, sdkHint);
        subscribers.computeIfAbsent(clientKey, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        connections.incrementAndGet();
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(e -> remove(subscriber));
        return subscriber;
    }

    /**
     * {@code current} was read before the subscriber was registered: if a broadcast reached the subscriber meanwhile,
     * it carried a newer payload, and sending {@code current} after it would roll the client back.
     */
    void sendInitial(Subscriber subscriber, ServedPayload current) {
        synchronized (subscriber) {
            if (!subscriber.updated) {
                send(subscriber, current);
            }
        }
    }

    public void broadcast(ServedPayload payload) {
        Set<Subscriber> set = subscribers.get(payload.clientKey());
        if (set == null) {
            return;
        }
        for (Subscriber subscriber : set) {
            synchronized (subscriber) {
                subscriber.updated = true;
                send(subscriber, payload);
            }
        }
    }

    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        subscribers.forEach((clientKey, set) -> set.forEach(subscriber -> {
            try {
                subscriber.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                remove(subscriber);
            }
        }));
    }

    private void send(Subscriber subscriber, ServedPayload payload) {
        try {
            if (payload.remoteEval()) {
                subscriber.emitter.send(SseEmitter.event().name(FEATURES_UPDATED_EVENT)
                        .data("{\"bundleHash\":\"" + payload.bundleHash() + "\"}", MediaType.APPLICATION_JSON));
            } else {
                subscriber.emitter.send(SseEmitter.event().name(FEATURES_EVENT).data(payload.body(), MediaType.APPLICATION_JSON));
            }
            deliveryRecorder.record(payload.clientKey(), payload.bundleHash(), DeliveryChannel.SSE, subscriber.sdkHint);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE subscriber for {} is gone: {}", payload.clientKey(), e.getMessage());
            remove(subscriber);
        }
    }

    private void remove(Subscriber subscriber) {
        Set<Subscriber> set = subscribers.get(subscriber.clientKey);
        if (set != null && set.remove(subscriber)) {
            connections.decrementAndGet();
        }
    }

    /** {@code updated}: a broadcast was sent to it (guarded by the subscriber's monitor). */
    static final class Subscriber {
        private final String clientKey;
        private final SseEmitter emitter;
        private final String sdkHint;
        private boolean updated;

        private Subscriber(String clientKey, SseEmitter emitter, String sdkHint) {
            this.clientKey = clientKey;
            this.emitter = emitter;
            this.sdkHint = sdkHint;
        }
    }
}
