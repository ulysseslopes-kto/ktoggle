package com.ktogroup.ktoggle.bundle;

import com.ktogroup.ktoggle.bundle.BundleActivation.Kind;
import com.ktogroup.ktoggle.bundle.PayloadCompiler.CompiledPayload;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns the committed configuration into bundles: for each SDK connection, compile &rarr; seal (canonicalize,
 * hash, sign) &rarr; store &rarr; append to the activation chain &rarr; broadcast after commit.
 *
 * <p>Idempotent: if the compiled payload equals the one currently active, nothing happens. That makes it safe
 * to call after every change and from the periodic reconciler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BundlePublisher {

    private final BundlePersistencePort persistence;
    private final PayloadCompiler compiler;
    private final BundleCodec codec;
    private final CanonicalJson canonicalJson;
    private final FeaturePersistencePort featurePersistence;
    private final SavedGroupService savedGroupService;
    private final SdkConnectionService sdkConnectionService;
    private final ActivationNotifier notifier;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** Publishes every unpinned connection whose payload changed. Runs in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<BundleActivation> publishAll(ChangeContext context) {
        persistence.lockPublication();
        return publishLocked(context, sdkConnectionService.findAll());
    }

    /**
     * Same as {@link #publishAll} for the given connections, inside a transaction that already holds the
     * publication lock (used by unpin).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BundleActivation> publishLocked(ChangeContext context, List<SdkConnection> connections) {
        Timer.Sample sample = Timer.start(meterRegistry);
        List<Feature> features = featurePersistence.findAllActive();
        Map<String, SavedGroup> groups = savedGroupService.findAllByKey();
        List<BundleActivation> activations = new ArrayList<>();
        for (SdkConnection connection : connections) {
            if (connection.pinnedBundleHash() != null) {
                log.debug("Connection {} is pinned to {}, skipping publication", connection.clientKey(), connection.pinnedBundleHash());
                continue;
            }
            publish(context, connection, compiler.compile(connection, features, groups)).ifPresent(activations::add);
        }
        sample.stop(meterRegistry.timer("ktoggle.bundle.publish"));
        notifyAfterCommit(activations);
        return activations;
    }

    /** Appends an activation to a connection's chain. Caller must hold the publication lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BundleActivation activate(ChangeContext context, String clientKey, String bundleHash, Kind kind) {
        Optional<BundleActivation> last = persistence.findLastActivation(clientKey);
        BundleActivation activation = new BundleActivation(
                Ids.newId(),
                clientKey,
                last.map(a -> a.position() + 1).orElse(1L),
                bundleHash,
                kind,
                context.changeId(),
                context.actor(),
                Ids.now(clock),
                context.reason(),
                last.map(BundleActivation::hash).orElse(null),
                null);
        BundleActivation hashed = activation.withHash(activationHash(activation));
        persistence.insertActivation(hashed);
        meterRegistry.counter("ktoggle.bundle.activations", "kind", kind.name()).increment();
        return hashed;
    }

    public String activationHash(BundleActivation activation) {
        return Hashes.sha256Hex(canonicalJson.canonicalize(activation.hashableView()));
    }

    public void notifyAfterCommit(List<BundleActivation> activations) {
        if (activations.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activations.forEach(a -> notifier.notifyActivated(a.clientKey(), a.bundleHash()));
            }
        });
    }

    private Optional<BundleActivation> publish(ChangeContext context, SdkConnection connection, CompiledPayload compiled) {
        BundleBody body = new BundleBody(
                BundleBody.CONTRACT_VERSION,
                new BundleBody.Target(connection.clientKey(), connection.environmentKey(), connection.projectKeys()),
                Evaluators.current(),
                compiled.sources(),
                compiled.payload());
        Optional<BundleActivation> last = persistence.findLastActivation(connection.clientKey());
        if (last.isPresent()) {
            String activePayloadHash = persistence.findByHash(last.get().bundleHash()).map(Bundle::payloadHash).orElse(null);
            if (codec.payloadHash(body).equals(activePayloadHash)) {
                return Optional.empty();
            }
        }
        Instant now = Ids.now(clock);
        Bundle bundle = codec.seal(body, now, context.actor());
        persistence.insertIfAbsent(bundle);
        BundleActivation activation = activate(context, connection.clientKey(), bundle.hash(), Kind.PUBLISH);
        log.info("Published bundle {} for {} (change {}, by {})", bundle.hash(), connection.clientKey(), context.changeId(),
                context.actor());
        return Optional.of(activation);
    }
}
