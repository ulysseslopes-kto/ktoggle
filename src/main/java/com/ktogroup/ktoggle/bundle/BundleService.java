package com.ktogroup.ktoggle.bundle;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.ChainVerification;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.bundle.BundleActivation.Kind;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import com.ktogroup.ktoggle.webhook.WebhookEvent;
import com.ktogroup.ktoggle.webhook.WebhookNotifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BundleService {

    private static final int VERIFY_PAGE = 500;

    private final BundlePersistencePort persistence;
    private final BundleCodec codec;
    private final BundlePublisher publisher;
    private final SdkConnectionService sdkConnectionService;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final WebhookNotifier webhooks;

    @Transactional(readOnly = true)
    public List<Bundle> bundles(String clientKey, int limit) {
        sdkConnectionService.get(clientKey);
        return persistence.findByClientKey(clientKey, limit);
    }

    /** Loads a bundle and fully verifies it (hash and signature) before returning it. */
    @Transactional(readOnly = true)
    public VerifiedBundle get(String hash) {
        Bundle bundle = persistence.findByHash(hash)
                .orElseThrow(() -> new NotFoundException("Bundle", hash));
        return new VerifiedBundle(bundle, codec.verify(bundle));
    }

    @Transactional(readOnly = true)
    public List<BundleActivation> activations(String clientKey, int limit) {
        sdkConnectionService.get(clientKey);
        return persistence.findActivations(clientKey, limit);
    }

    /** The bundle a client key was serving at a given instant (resolved from the activation chain). */
    @Transactional(readOnly = true)
    public BundleActivation activationAt(String clientKey, Instant instant) {
        return persistence.findActivationAt(clientKey, instant)
                .orElseThrow(() -> new NotFoundException(MessageCode.ENTITY_NOT_FOUND,
                        "No bundle was active for %s at %s".formatted(clientKey, instant)));
    }

    /**
     * Emergency rollback: re-activates a previous bundle of the connection and pins it, suspending automatic
     * publication until {@link #unpin}. Configuration changes keep being audited and versioned meanwhile.
     */
    @Transactional
    public BundleActivation rollback(String clientKey, String bundleHash) {
        persistence.lockPublication();
        SdkConnection connection = sdkConnectionService.get(clientKey);
        VerifiedBundle target = get(bundleHash);
        if (!target.bundle().clientKey().equals(clientKey)) {
            throw ValidationException.of("Bundle %s does not belong to %s".formatted(bundleHash, clientKey));
        }
        ChangeContext context = changeContextProvider.current();
        if (context.reason() == null) {
            throw ValidationException.of("A rollback requires a reason (header %s)".formatted(ChangeContextProvider.REASON_HEADER));
        }
        BundleActivation activation = publisher.activate(context, clientKey, bundleHash, Kind.ROLLBACK);
        sdkConnectionService.setPinnedBundle(clientKey, bundleHash);
        auditService.record(context, AuditAction.ROLLBACK_BUNDLE, EntityType.SDK_CONNECTION, clientKey,
                Map.of("pinnedBundleHash", Objects.toString(connection.pinnedBundleHash(), "")),
                Map.of("pinnedBundleHash", bundleHash, "activation", activation.hash()));
        publisher.notifyAfterCommit(List.of(activation));
        webhooks.notify(WebhookEvent.BUNDLE_ROLLED_BACK, context.actor(),
                "%s rolled %s back to bundle %s".formatted(context.actor(), clientKey, bundleHash.substring(0, 12)),
                "/sdk-connections/" + clientKey,
                Map.of("clientKey", clientKey, "bundleHash", bundleHash, "reason", context.reason()));
        return activation;
    }

    /** Releases a pin and immediately publishes the current configuration for the connection. */
    @Transactional
    public List<BundleActivation> unpin(String clientKey) {
        persistence.lockPublication();
        SdkConnection connection = sdkConnectionService.get(clientKey);
        if (connection.pinnedBundleHash() == null) {
            throw new ConflictException(MessageCode.VALIDATION_ERROR, "Connection %s is not pinned".formatted(clientKey));
        }
        ChangeContext context = changeContextProvider.current();
        SdkConnection unpinned = sdkConnectionService.setPinnedBundle(clientKey, null);
        auditService.record(context, AuditAction.UNPIN_BUNDLE, EntityType.SDK_CONNECTION, clientKey,
                Map.of("pinnedBundleHash", connection.pinnedBundleHash()), Map.of("pinnedBundleHash", ""));
        return publisher.publishLocked(context, List.of(unpinned));
    }

    /** Recomputes the activation chain of a connection and checks every referenced bundle. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(String clientKey) {
        sdkConnectionService.get(clientKey);
        long checked = 0;
        long afterPosition = 0;
        String expectedPrev = null;
        List<BundleActivation> page;
        do {
            page = persistence.findActivationsAfter(clientKey, afterPosition, VERIFY_PAGE);
            for (BundleActivation activation : page) {
                checked++;
                if (activation.position() != checked) {
                    return ChainVerification.broken(checked, activation.position(), "Gap in positions");
                }
                if (!Objects.equals(expectedPrev, activation.prevHash())) {
                    return ChainVerification.broken(checked, activation.position(), "prevHash does not link to previous activation");
                }
                if (!publisher.activationHash(activation).equals(activation.hash())) {
                    return ChainVerification.broken(checked, activation.position(), "Activation content does not match its hash");
                }
                try {
                    get(activation.bundleHash());
                } catch (RuntimeException e) {
                    return ChainVerification.broken(checked, activation.position(), e.getMessage());
                }
                expectedPrev = activation.hash();
                afterPosition = activation.position();
            }
        } while (page.size() == VERIFY_PAGE);
        return ChainVerification.ok(checked);
    }

    public record VerifiedBundle(Bundle bundle, BundleBody body) {
    }
}
