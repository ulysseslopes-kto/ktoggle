package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.ChainVerification;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.bundle.BundleActivation.Kind;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.IntegrityException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import com.ktogroup.ktoggle.webhook.WebhookNotifier;
import com.ktogroup.ktoggle.ztest.TestBundles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BundleServiceTest {

    private final TestBundles testBundles = new TestBundles();
    private final BundlePersistencePort persistence = mock(BundlePersistencePort.class);
    private final BundleCodec codec = mock(BundleCodec.class);
    private final BundlePublisher publisher = mock(BundlePublisher.class);
    private final SdkConnectionService sdkConnections = mock(SdkConnectionService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final BundleService service = new BundleService(persistence, codec, publisher, sdkConnections, auditService,
            changeContextProvider, mock(WebhookNotifier.class));

    private Bundle bundleOfA;

    @BeforeEach
    void setUp() {
        bundleOfA = testBundles.bundle("sdk-a", true);
        when(sdkConnections.get("sdk-a")).thenReturn(connection("sdk-a", null));
        when(persistence.findByHash(bundleOfA.hash())).thenReturn(Optional.of(bundleOfA));
        when(codec.verify(bundleOfA)).thenReturn(testBundles.body("sdk-a", true));
        when(publisher.activationHash(any())).thenAnswer(invocation -> ((BundleActivation) invocation.getArgument(0)).hash());
    }

    @Test
    void a_bundle_is_verified_before_it_is_returned() {
        BundleService.VerifiedBundle verified = service.get(bundleOfA.hash());

        assertThat(verified.bundle()).isEqualTo(bundleOfA);
        assertThat(verified.body().target().clientKey()).isEqualTo("sdk-a");
        verify(codec).verify(bundleOfA);
    }

    @Test
    void an_unknown_bundle_is_not_found() {
        when(persistence.findByHash("0".repeat(64))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("0".repeat(64))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void a_tampered_bundle_is_never_returned() {
        when(codec.verify(bundleOfA)).thenThrow(new IntegrityException("bad signature"));

        assertThatThrownBy(() -> service.get(bundleOfA.hash())).isInstanceOf(IntegrityException.class);
    }

    @Test
    void listings_require_an_existing_connection() {
        when(sdkConnections.get("sdk-x")).thenThrow(new NotFoundException("SDK connection", "sdk-x"));
        when(persistence.findByClientKey("sdk-a", 5)).thenReturn(List.of(bundleOfA));
        when(persistence.findActivations("sdk-a", 5)).thenReturn(List.of());

        assertThat(service.bundles("sdk-a", 5)).containsExactly(bundleOfA);
        assertThat(service.activations("sdk-a", 5)).isEmpty();
        assertThatThrownBy(() -> service.bundles("sdk-x", 5)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.activations("sdk-x", 5)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.verifyChain("sdk-x")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void the_activation_in_force_at_an_instant_is_resolved_or_not_found() {
        Instant instant = Instant.parse("2026-10-05T12:30:00Z");
        BundleActivation activation = TestBundles.activation("sdk-a", 1, bundleOfA.hash(), null);
        when(persistence.findActivationAt("sdk-a", instant)).thenReturn(Optional.of(activation));
        when(persistence.findActivationAt("sdk-a", instant.minusSeconds(3600))).thenReturn(Optional.empty());

        assertThat(service.activationAt("sdk-a", instant)).isEqualTo(activation);
        assertThatThrownBy(() -> service.activationAt("sdk-a", instant.minusSeconds(3600)))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("No bundle was active");
    }

    @Test
    void rollback_without_a_reason_is_refused() {
        when(changeContextProvider.current()).thenReturn(new ChangeContext(Ids.newId(), "alice", null));

        assertThatThrownBy(() -> service.rollback("sdk-a", bundleOfA.hash()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("requires a reason");

        verify(publisher, never()).activate(any(), any(), any(), any());
        verify(sdkConnections, never()).setPinnedBundle(any(), any());
    }

    @Test
    void rollback_to_a_bundle_of_another_client_key_is_refused() {
        Bundle other = testBundles.bundle("sdk-b", false);
        when(persistence.findByHash(other.hash())).thenReturn(Optional.of(other));
        when(codec.verify(other)).thenReturn(testBundles.body("sdk-b", false));
        when(changeContextProvider.current()).thenReturn(new ChangeContext(Ids.newId(), "alice", "incident"));

        assertThatThrownBy(() -> service.rollback("sdk-a", other.hash()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("does not belong to sdk-a");

        verify(publisher, never()).activate(any(), any(), any(), any());
    }

    @Test
    void rollback_activates_the_bundle_pins_the_connection_audits_and_notifies_after_commit() {
        ChangeContext context = new ChangeContext(Ids.newId(), "alice", "incident INC-1");
        when(changeContextProvider.current()).thenReturn(context);
        when(sdkConnections.get("sdk-a")).thenReturn(connection("sdk-a", "c".repeat(64)));
        BundleActivation activation = TestBundles.activation("sdk-a", 4, bundleOfA.hash(), "p");
        when(publisher.activate(context, "sdk-a", bundleOfA.hash(), Kind.ROLLBACK)).thenReturn(activation);

        BundleActivation result = service.rollback("sdk-a", bundleOfA.hash());

        assertThat(result).isEqualTo(activation);
        verify(persistence).lockPublication();
        verify(sdkConnections).setPinnedBundle("sdk-a", bundleOfA.hash());
        verify(auditService).record(eq(context), eq(AuditAction.ROLLBACK_BUNDLE), eq(EntityType.SDK_CONNECTION), eq("sdk-a"),
                eq(java.util.Map.of("pinnedBundleHash", "c".repeat(64))),
                eq(java.util.Map.of("pinnedBundleHash", bundleOfA.hash(), "activation", activation.hash())));
        verify(publisher).notifyAfterCommit(List.of(activation));
    }

    @Test
    void rollback_audit_of_a_previously_unpinned_connection_records_an_empty_previous_pin() {
        ChangeContext context = new ChangeContext(Ids.newId(), "alice", "incident");
        when(changeContextProvider.current()).thenReturn(context);
        when(publisher.activate(any(), any(), any(), any())).thenReturn(TestBundles.activation("sdk-a", 2, bundleOfA.hash(), "p"));

        service.rollback("sdk-a", bundleOfA.hash());

        verify(auditService).record(eq(context), eq(AuditAction.ROLLBACK_BUNDLE), eq(EntityType.SDK_CONNECTION), eq("sdk-a"),
                eq(java.util.Map.of("pinnedBundleHash", "")), any());
    }

    @Test
    void unpin_of_a_connection_that_is_not_pinned_is_a_conflict() {
        assertThatThrownBy(() -> service.unpin("sdk-a")).isInstanceOf(ConflictException.class).hasMessageContaining("is not pinned");

        verify(sdkConnections, never()).setPinnedBundle(any(), any());
        verify(publisher, never()).publishLocked(any(), any());
    }

    @Test
    void unpin_releases_the_pin_audits_it_and_publishes_the_current_configuration() {
        ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
        SdkConnection pinned = connection("sdk-a", bundleOfA.hash());
        SdkConnection released = connection("sdk-a", null);
        BundleActivation published = TestBundles.activation("sdk-a", 5, bundleOfA.hash(), "p");
        when(sdkConnections.get("sdk-a")).thenReturn(pinned);
        when(changeContextProvider.current()).thenReturn(context);
        when(sdkConnections.setPinnedBundle("sdk-a", null)).thenReturn(released);
        when(publisher.publishLocked(context, List.of(released))).thenReturn(List.of(published));

        assertThat(service.unpin("sdk-a")).containsExactly(published);

        verify(auditService).record(eq(context), eq(AuditAction.UNPIN_BUNDLE), eq(EntityType.SDK_CONNECTION), eq("sdk-a"),
                eq(java.util.Map.of("pinnedBundleHash", bundleOfA.hash())), eq(java.util.Map.of("pinnedBundleHash", "")));
    }

    @Test
    void an_intact_activation_chain_verifies() {
        List<BundleActivation> chain = chain(3);
        stubChain(chain);

        assertThat(service.verifyChain("sdk-a")).isEqualTo(ChainVerification.ok(3));
    }

    @Test
    void a_chain_without_activations_verifies() {
        stubChain(List.of());

        assertThat(service.verifyChain("sdk-a")).isEqualTo(ChainVerification.ok(0));
    }

    @Test
    void a_long_chain_is_walked_page_by_page() {
        stubChain(chain(501));

        assertThat(service.verifyChain("sdk-a")).isEqualTo(ChainVerification.ok(501));
    }

    @Test
    void a_gap_in_positions_is_reported() {
        List<BundleActivation> chain = chain(3);
        chain.remove(1);
        stubChain(chain);

        ChainVerification verification = service.verifyChain("sdk-a");

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(3);
        assertThat(verification.message()).isEqualTo("Gap in positions");
    }

    @Test
    void an_activation_that_does_not_link_to_its_predecessor_is_reported() {
        List<BundleActivation> chain = chain(3);
        BundleActivation second = chain.get(1);
        chain.set(1, new BundleActivation(second.id(), second.clientKey(), second.position(), second.bundleHash(), second.kind(),
                second.changeId(), second.activatedBy(), second.activatedAt(), second.reason(), "f".repeat(64), second.hash()));
        stubChain(chain);

        ChainVerification verification = service.verifyChain("sdk-a");

        assertThat(verification.brokenAt()).isEqualTo(2);
        assertThat(verification.message()).contains("prevHash does not link");
    }

    @Test
    void an_activation_whose_content_no_longer_matches_its_hash_is_reported() {
        stubChain(chain(3));
        doAnswer(invocation -> {
            BundleActivation activation = invocation.getArgument(0);
            return activation.position() == 2 ? "0".repeat(64) : activation.hash();
        }).when(publisher).activationHash(any());

        ChainVerification verification = service.verifyChain("sdk-a");

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(2);
        assertThat(verification.message()).contains("does not match its hash");
    }

    @Test
    void an_activation_pointing_to_a_bundle_that_fails_verification_is_reported_with_the_reason() {
        stubChain(chain(3));
        when(codec.verify(bundleOfA)).thenThrow(new IntegrityException("signature invalid"));

        ChainVerification verification = service.verifyChain("sdk-a");

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(1);
        assertThat(verification.message()).isEqualTo("signature invalid");
    }

    private List<BundleActivation> chain(int size) {
        List<BundleActivation> chain = new ArrayList<>();
        String prev = null;
        for (int position = 1; position <= size; position++) {
            BundleActivation activation = TestBundles.activation("sdk-a", position, bundleOfA.hash(), prev);
            chain.add(activation);
            prev = activation.hash();
        }
        return chain;
    }

    private void stubChain(List<BundleActivation> chain) {
        when(persistence.findActivationsAfter(eq("sdk-a"), anyLong(), anyInt())).thenAnswer(invocation -> {
            long after = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            return chain.stream().filter(a -> a.position() > after).limit(limit).toList();
        });
    }

    private static SdkConnection connection(String clientKey, String pinned) {
        return new SdkConnection(clientKey, "web", "prod", List.of(), pinned, Instant.now(), Instant.now(), 0L);
    }
}
