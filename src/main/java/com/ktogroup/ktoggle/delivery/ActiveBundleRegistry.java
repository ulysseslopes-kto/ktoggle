package com.ktogroup.ktoggle.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivatedEvent;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleBody;
import com.ktogroup.ktoggle.bundle.BundleCodec;
import com.ktogroup.ktoggle.bundle.BundlePersistencePort;
import com.ktogroup.ktoggle.commons.exception.IntegrityException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory view of what every client key serves, kept per pod. SDK requests never hit the database.
 *
 * <p>Updated on activation notifications and by a periodic resync from the database (safety net for lost
 * notifications). A bundle is only ever served after full verification (canonical form, hash, signature); if a
 * new bundle fails verification, the pod keeps serving the last valid one and raises
 * {@code ktoggle.bundle.integrity_failures}.
 */
@Slf4j
@Component
public class ActiveBundleRegistry {

    private final BundlePersistencePort bundles;
    private final BundleCodec codec;
    private final ObjectMapper objectMapper;
    private final SseHub sseHub;
    private final MeterRegistry meterRegistry;
    private final Map<String, ServedPayload> served = new ConcurrentHashMap<>();

    public ActiveBundleRegistry(BundlePersistencePort bundles, BundleCodec codec, ObjectMapper objectMapper, SseHub sseHub,
                                MeterRegistry meterRegistry) {
        this.bundles = bundles;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.sseHub = sseHub;
        this.meterRegistry = meterRegistry;
        Gauge.builder("ktoggle.delivery.client_keys", served, Map::size).register(meterRegistry);
    }

    /** Current payload of a client key, loading it on first use. Empty if the key has never been published. */
    public Optional<ServedPayload> get(String clientKey) {
        ServedPayload current = served.get(clientKey);
        if (current != null) {
            return Optional.of(current);
        }
        return bundles.findLastActivation(clientKey).flatMap(this::load);
    }

    @EventListener
    public void onBundleActivated(BundleActivatedEvent event) {
        bundles.findLastActivation(event.clientKey()).ifPresent(this::loadAndBroadcast);
    }

    /** Every pod resyncs (no cluster lock): each one owns its in-memory view. */
    @Scheduled(fixedDelayString = "${ktoggle.delivery.sync-interval:PT15S}", initialDelayString = "${ktoggle.delivery.sync-interval:PT15S}")
    public void resync() {
        List<BundleActivation> current = bundles.findCurrentActivations();
        for (BundleActivation activation : current) {
            ServedPayload known = served.get(activation.clientKey());
            if (known == null || known.activationPosition() != activation.position()) {
                loadAndBroadcast(activation);
            }
        }
    }

    private void loadAndBroadcast(BundleActivation activation) {
        load(activation).ifPresent(sseHub::broadcast);
    }

    private synchronized Optional<ServedPayload> load(BundleActivation activation) {
        ServedPayload known = served.get(activation.clientKey());
        if (known != null && known.activationPosition() >= activation.position()) {
            return Optional.of(known);
        }
        try {
            Bundle bundle = bundles.findByHash(activation.bundleHash())
                    .orElseThrow(() -> new IntegrityException("Activated bundle %s is missing".formatted(activation.bundleHash())));
            BundleBody body = codec.verify(bundle);
            ServedPayload payload = new ServedPayload(activation.clientKey(), bundle.hash(), activation.position(),
                    activation.activatedAt(), render(body, activation));
            served.put(activation.clientKey(), payload);
            return Optional.of(payload);
        } catch (IntegrityException e) {
            meterRegistry.counter("ktoggle.bundle.integrity_failures").increment();
            log.error("Refusing to serve bundle {} for {}: {}. Still serving {}", activation.bundleHash(),
                    activation.clientKey(), e.getMessage(), known == null ? "nothing" : known.bundleHash());
            return Optional.ofNullable(known);
        }
    }

    /** GrowthBook API response shape; {@code bundleHash} is an extra field SDKs ignore. */
    private String render(BundleBody body, BundleActivation activation) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", 200);
        response.set("features", body.payload().get("features"));
        response.putArray("experiments");
        response.put("dateUpdated", activation.activatedAt().toString());
        response.put("bundleHash", activation.bundleHash());
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to render payload", e);
        }
    }
}
