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

    private final Map<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final DeliveryRecorder deliveryRecorder;

    public SseHub(DeliveryRecorder deliveryRecorder, MeterRegistry meterRegistry) {
        this.deliveryRecorder = deliveryRecorder;
        Gauge.builder("ktoggle.delivery.sse_connections", connections, AtomicInteger::get).register(meterRegistry);
    }

    public SseEmitter subscribe(ServedPayload current, String sdkHint) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, sdkHint);
        subscribers.computeIfAbsent(current.clientKey(), k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        connections.incrementAndGet();
        Runnable remove = () -> {
            Set<Subscriber> set = subscribers.get(current.clientKey());
            if (set != null && set.remove(subscriber)) {
                connections.decrementAndGet();
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(subscriber, current, remove);
        return emitter;
    }

    public void broadcast(ServedPayload payload) {
        Set<Subscriber> set = subscribers.get(payload.clientKey());
        if (set == null) {
            return;
        }
        for (Subscriber subscriber : set) {
            send(subscriber, payload, () -> {
                if (set.remove(subscriber)) {
                    connections.decrementAndGet();
                }
            });
        }
    }

    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        subscribers.forEach((clientKey, set) -> set.forEach(subscriber -> {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                if (set.remove(subscriber)) {
                    connections.decrementAndGet();
                }
            }
        }));
    }

    private void send(Subscriber subscriber, ServedPayload payload, Runnable onFailure) {
        try {
            subscriber.emitter().send(SseEmitter.event().name(FEATURES_EVENT).data(payload.body(), MediaType.APPLICATION_JSON));
            deliveryRecorder.record(payload.clientKey(), payload.bundleHash(), DeliveryChannel.SSE, subscriber.sdkHint());
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE subscriber for {} is gone: {}", payload.clientKey(), e.getMessage());
            onFailure.run();
        }
    }

    private record Subscriber(SseEmitter emitter, String sdkHint) {
    }
}
