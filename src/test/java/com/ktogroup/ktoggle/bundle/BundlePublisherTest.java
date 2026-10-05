package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.BundleActivation.Kind;
import com.ktogroup.ktoggle.bundle.PayloadCompiler.CompiledPayload;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import com.ktogroup.ktoggle.ztest.TestBundles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BundlePublisherTest {

    private final TestBundles testBundles = new TestBundles();
    private final BundlePersistencePort persistence = mock(BundlePersistencePort.class);
    private final PayloadCompiler compiler = mock(PayloadCompiler.class);
    private final FeaturePersistencePort featurePersistence = mock(FeaturePersistencePort.class);
    private final SavedGroupService savedGroupService = mock(SavedGroupService.class);
    private final SdkConnectionService sdkConnectionService = mock(SdkConnectionService.class);
    private final ActivationNotifier notifier = mock(ActivationNotifier.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", "why");
    private final BundlePublisher publisher = new BundlePublisher(persistence, compiler, testBundles.codec(), testBundles.canonicalJson(),
            featurePersistence, savedGroupService, sdkConnectionService, notifier, new SimpleMeterRegistry(),
            Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(featurePersistence.findAllActive()).thenReturn(List.of());
        when(savedGroupService.findAllByKey()).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void the_first_publication_of_a_connection_starts_its_chain() {
        SdkConnection connection = connection("sdk-a", null);
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.empty());

        List<BundleActivation> activations = publisher.publishLocked(context, List.of(connection));

        assertThat(activations).hasSize(1);
        BundleActivation activation = activations.getFirst();
        assertThat(activation.position()).isEqualTo(1);
        assertThat(activation.prevHash()).isNull();
        assertThat(activation.kind()).isEqualTo(Kind.PUBLISH);
        assertThat(activation.changeId()).isEqualTo(context.changeId());
        assertThat(activation.activatedBy()).isEqualTo("alice");
        assertThat(activation.reason()).isEqualTo("why");
        assertThat(publisher.activationHash(activation)).isEqualTo(activation.hash());
        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(persistence).insertIfAbsent(bundle.capture());
        assertThat(bundle.getValue().hash()).isEqualTo(activation.bundleHash());
        assertThat(bundle.getValue().createdBy()).isEqualTo("alice");
        assertThat(testBundles.codec().verify(bundle.getValue()).target().clientKey()).isEqualTo("sdk-a");
    }

    @Test
    void a_changed_payload_is_appended_to_the_chain_after_the_current_activation() {
        SdkConnection connection = connection("sdk-a", null);
        Bundle active = bundleFor("sdk-a", false);
        BundleActivation last = TestBundles.activation("sdk-a", 7, active.hash(), "p");
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(last));
        when(persistence.findByHash(active.hash())).thenReturn(Optional.of(active));

        BundleActivation activation = publisher.publishLocked(context, List.of(connection)).getFirst();

        assertThat(activation.position()).isEqualTo(8);
        assertThat(activation.prevHash()).isEqualTo(last.hash());
        assertThat(activation.bundleHash()).isNotEqualTo(active.hash());
    }

    @Test
    void no_new_bundle_is_created_when_the_payload_is_unchanged() {
        SdkConnection connection = connection("sdk-a", null);
        Bundle active = bundleFor("sdk-a", true);
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 3, active.hash(), "p")));
        when(persistence.findByHash(active.hash())).thenReturn(Optional.of(active));

        List<BundleActivation> activations = publisher.publishLocked(context, List.of(connection));

        assertThat(activations).isEmpty();
        verify(persistence, never()).insertIfAbsent(any());
        verify(persistence, never()).insertActivation(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void if_the_active_bundle_cannot_be_loaded_the_payload_is_published_again() {
        SdkConnection connection = connection("sdk-a", null);
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 3, "e".repeat(64), "p")));
        when(persistence.findByHash("e".repeat(64))).thenReturn(Optional.empty());

        assertThat(publisher.publishLocked(context, List.of(connection))).hasSize(1);
    }

    @Test
    void pinned_connections_are_skipped_while_the_others_are_published() {
        SdkConnection pinned = connection("sdk-pinned", "d".repeat(64));
        SdkConnection free = connection("sdk-free", null);
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-free")).thenReturn(Optional.empty());

        List<BundleActivation> activations = publisher.publishLocked(context, List.of(pinned, free));

        assertThat(activations).extracting(BundleActivation::clientKey).containsExactly("sdk-free");
        verify(compiler, never()).compile(org.mockito.ArgumentMatchers.eq(pinned), any(), any(), any());
        verify(persistence, never()).findLastActivation("sdk-pinned");
    }

    @Test
    void activations_are_broadcast_only_after_the_transaction_commits() {
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.empty());

        BundleActivation activation = publisher.publishLocked(context, List.of(connection("sdk-a", null))).getFirst();

        verify(notifier, never()).notifyActivated(any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(notifier).notifyActivated("sdk-a", activation.bundleHash());
    }

    @Test
    void publish_all_takes_the_publication_lock_and_publishes_every_connection() {
        when(sdkConnectionService.findAll()).thenReturn(List.of(connection("sdk-a", null), connection("sdk-b", null)));
        when(compiler.compile(any(), any(), any(), any())).thenReturn(compiled(true));
        when(persistence.findLastActivation(any())).thenReturn(Optional.empty());

        List<BundleActivation> activations = publisher.publishAll(context);

        verify(persistence).lockPublication();
        assertThat(activations).extracting(BundleActivation::clientKey).containsExactly("sdk-a", "sdk-b");
    }

    @Test
    void rollback_activations_continue_the_chain_with_their_own_kind() {
        BundleActivation last = TestBundles.activation("sdk-a", 2, "b".repeat(64), "p");
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(last));

        BundleActivation activation = publisher.activate(context, "sdk-a", "a".repeat(64), Kind.ROLLBACK);

        assertThat(activation.position()).isEqualTo(3);
        assertThat(activation.prevHash()).isEqualTo(last.hash());
        assertThat(activation.kind()).isEqualTo(Kind.ROLLBACK);
        assertThat(activation.bundleHash()).isEqualTo("a".repeat(64));
        verify(persistence).insertActivation(activation);
    }

    @Test
    void nothing_is_scheduled_when_there_is_nothing_to_notify() {
        publisher.notifyAfterCommit(List.of());

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private Bundle bundleFor(String clientKey, boolean featureDefault) {
        return testBundles.codec().seal(testBundles.body(clientKey, featureDefault), TestBundles.CREATED_AT, "alice");
    }

    private static SdkConnection connection(String clientKey, String pinned) {
        return new SdkConnection(clientKey, "web", "prod", List.of(), pinned, Instant.now(), Instant.now(), 0L);
    }

    /** Same payload as {@link TestBundles#body(String, boolean)}, so its payload hash matches the bundles built by the helper. */
    private static CompiledPayload compiled(boolean featureDefault) {
        ObjectNode features = JsonNodeFactory.instance.objectNode();
        features.putObject("checkout").put("defaultValue", featureDefault);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("features", features);
        return new CompiledPayload(payload, Map.of("checkout", 1));
    }
}
