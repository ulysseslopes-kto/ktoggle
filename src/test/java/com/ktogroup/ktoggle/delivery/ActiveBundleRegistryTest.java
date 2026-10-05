package com.ktogroup.ktoggle.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivatedEvent;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.ActivationNotifier;
import com.ktogroup.ktoggle.bundle.BundlePersistencePort;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import com.ktogroup.ktoggle.ztest.TestBundles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActiveBundleRegistryTest {

    private final TestBundles testBundles = new TestBundles();
    private final BundlePersistencePort persistence = mock(BundlePersistencePort.class);
    private final SseHub sseHub = mock(SseHub.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SdkConnectionService connections = mock(SdkConnectionService.class);
    private final ActiveBundleRegistry registry = new ActiveBundleRegistry(persistence, testBundles.codec(),
            testBundles.objectMapper(), sseHub, meters, connections, mock(ActivationNotifier.class));

    @Test
    void a_verified_bundle_is_served_in_the_growthbook_response_shape() throws Exception {
        Bundle bundle = publish("sdk-a", 1, true);

        ServedPayload served = registry.get("sdk-a").orElseThrow();

        assertThat(served.bundleHash()).isEqualTo(bundle.hash());
        assertThat(served.activationPosition()).isEqualTo(1);
        assertThat(served.etag()).isEqualTo("\"" + bundle.hash() + "\"");
        JsonNode body = testBundles.objectMapper().readTree(served.body());
        assertThat(body.path("status").asInt()).isEqualTo(200);
        assertThat(body.path("features").path("checkout").path("defaultValue").asBoolean()).isTrue();
        assertThat(body.path("experiments")).isEmpty();
        assertThat(body.path("bundleHash").asText()).isEqualTo(bundle.hash());
        assertThat(body.path("dateUpdated").asText()).isEqualTo(TestBundles.CREATED_AT.plusSeconds(1).toString());
    }

    @Test
    void encrypted_connections_get_encrypted_features_and_a_key_rotation_re_renders_them() throws Exception {
        Bundle bundle = publish("sdk-a", 1, true);
        SdkConnection encrypted = new SdkConnection("sdk-a", "web", "prd", List.of(), null, true, "AAECAwQFBgcICQoLDA0ODw==", false,
                TestBundles.CREATED_AT, TestBundles.CREATED_AT, 0L);
        when(connections.find("sdk-a")).thenReturn(Optional.of(encrypted));

        ServedPayload served = registry.get("sdk-a").orElseThrow();
        JsonNode body = testBundles.objectMapper().readTree(served.body());
        assertThat(body.path("features")).as("nothing readable in clear").isEmpty();
        assertThat(body.path("bundleHash").asText()).isEqualTo(bundle.hash());
        JsonNode features = testBundles.objectMapper().readTree(
                PayloadEncryption.decrypt(body.path("encryptedFeatures").asText(), encrypted.decryptionKey()));
        assertThat(features.path("checkout").path("defaultValue").asBoolean()).isTrue();

        SdkConnection rotated = encrypted.withDecryptionKey("DwAOAA0ADAALAAoACQAIAA==");
        when(connections.find("sdk-a")).thenReturn(Optional.of(rotated));
        when(connections.findAll()).thenReturn(List.of(rotated));
        when(persistence.findCurrentActivations()).thenReturn(List.of(TestBundles.activation("sdk-a", 1, bundle.hash(), null)));
        registry.resync();

        ServedPayload rerendered = registry.get("sdk-a").orElseThrow();
        assertThat(rerendered.deliveryMode()).isEqualTo(rotated.deliveryMode()).isNotEqualTo(served.deliveryMode());
        String encryptedFeatures = testBundles.objectMapper().readTree(rerendered.body()).path("encryptedFeatures").asText();
        assertThat(PayloadEncryption.decrypt(encryptedFeatures, rotated.decryptionKey())).contains("checkout");
        verify(sseHub).broadcast(rerendered);
    }

    @Test
    void the_loaded_payload_is_cached_in_memory() {
        publish("sdk-a", 1, true);

        registry.get("sdk-a");
        registry.get("sdk-a");

        verify(persistence, times(1)).findLastActivation("sdk-a");
        verify(persistence, times(1)).findByHash(any());
    }

    @Test
    void a_client_key_that_was_never_published_serves_nothing() {
        when(persistence.findLastActivation("sdk-none")).thenReturn(Optional.empty());

        assertThat(registry.get("sdk-none")).isEmpty();
    }

    @Test
    void an_activation_notification_loads_the_new_bundle_and_broadcasts_it() {
        publish("sdk-a", 1, true);
        registry.get("sdk-a");
        Bundle next = publish("sdk-a", 2, false);

        registry.onBundleActivated(new BundleActivatedEvent("sdk-a", next.hash()));

        assertThat(registry.get("sdk-a").orElseThrow().bundleHash()).isEqualTo(next.hash());
        ArgumentCaptor<ServedPayload> broadcast = ArgumentCaptor.forClass(ServedPayload.class);
        verify(sseHub).broadcast(broadcast.capture());
        assertThat(broadcast.getValue().bundleHash()).isEqualTo(next.hash());
    }

    @Test
    void a_bundle_that_fails_verification_is_never_served_and_the_previous_one_stays() {
        Bundle good = publish("sdk-a", 1, true);
        registry.get("sdk-a");
        Bundle forged = testBundles.tampered(testBundles.bundle("sdk-a", true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 2, forged.hash(), "x")));
        when(persistence.findByHash(forged.hash())).thenReturn(Optional.of(forged));

        registry.onBundleActivated(new BundleActivatedEvent("sdk-a", forged.hash()));

        assertThat(registry.get("sdk-a").orElseThrow().bundleHash()).isEqualTo(good.hash());
        assertThat(meters.get("ktoggle.bundle.integrity_failures").counter().count()).isEqualTo(1);
    }

    @Test
    void a_forged_first_bundle_leaves_the_client_key_unserved() {
        Bundle forged = testBundles.tampered(testBundles.bundle("sdk-a", true));
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 1, forged.hash(), null)));
        when(persistence.findByHash(forged.hash())).thenReturn(Optional.of(forged));

        assertThat(registry.get("sdk-a")).isEmpty();
        assertThat(meters.get("ktoggle.bundle.integrity_failures").counter().count()).isEqualTo(1);
    }

    @Test
    void an_activation_pointing_to_a_missing_bundle_counts_as_an_integrity_failure() {
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 1, "f".repeat(64), null)));
        when(persistence.findByHash("f".repeat(64))).thenReturn(Optional.empty());

        assertThat(registry.get("sdk-a")).isEmpty();
        assertThat(meters.get("ktoggle.bundle.integrity_failures").counter().count()).isEqualTo(1);
    }

    @Test
    void an_older_activation_never_replaces_a_newer_one() {
        Bundle newer = publish("sdk-a", 5, false);
        registry.get("sdk-a");
        Bundle older = testBundles.bundle("sdk-a", true);
        when(persistence.findLastActivation("sdk-a")).thenReturn(Optional.of(TestBundles.activation("sdk-a", 4, older.hash(), null)));

        registry.onBundleActivated(new BundleActivatedEvent("sdk-a", older.hash()));

        assertThat(registry.get("sdk-a").orElseThrow().bundleHash()).isEqualTo(newer.hash());
        verify(persistence, never()).findByHash(older.hash());
    }

    @Test
    void notifications_for_unpublished_client_keys_are_ignored() {
        when(persistence.findLastActivation("sdk-none")).thenReturn(Optional.empty());

        registry.onBundleActivated(new BundleActivatedEvent("sdk-none", "x"));

        verify(sseHub, never()).broadcast(any());
    }

    @Test
    void resync_reloads_only_the_client_keys_whose_activation_position_changed() {
        Bundle a1 = publish("sdk-a", 1, true);
        registry.get("sdk-a");
        Bundle b1 = testBundles.bundle("sdk-b", true);
        BundleActivation b1Activation = TestBundles.activation("sdk-b", 1, b1.hash(), null);
        when(persistence.findByHash(b1.hash())).thenReturn(Optional.of(b1));
        when(persistence.findCurrentActivations()).thenReturn(List.of(TestBundles.activation("sdk-a", 1, a1.hash(), null), b1Activation));

        registry.resync();

        verify(persistence, times(1)).findByHash(a1.hash());
        verify(persistence, times(1)).findByHash(b1.hash());
        ArgumentCaptor<ServedPayload> broadcast = ArgumentCaptor.forClass(ServedPayload.class);
        verify(sseHub, times(1)).broadcast(broadcast.capture());
        assertThat(broadcast.getValue().clientKey()).isEqualTo("sdk-b");
        assertThat(registry.get("sdk-b").orElseThrow().bundleHash()).isEqualTo(b1.hash());
    }

    @Test
    void resync_picks_up_a_newer_position_of_an_already_served_client_key() {
        publish("sdk-a", 1, true);
        registry.get("sdk-a");
        Bundle a2 = testBundles.bundle("sdk-a", false);
        when(persistence.findByHash(a2.hash())).thenReturn(Optional.of(a2));
        when(persistence.findCurrentActivations()).thenReturn(List.of(TestBundles.activation("sdk-a", 2, a2.hash(), null)));

        registry.resync();

        assertThat(registry.get("sdk-a").orElseThrow().bundleHash()).isEqualTo(a2.hash());
        assertThat(meters.get("ktoggle.delivery.client_keys").gauge().value()).isEqualTo(1);
    }

    private Bundle publish(String clientKey, long position, boolean featureDefault) {
        Bundle bundle = testBundles.bundle(clientKey, featureDefault);
        when(persistence.findLastActivation(clientKey)).thenReturn(Optional.of(TestBundles.activation(clientKey, position, bundle.hash(), null)));
        when(persistence.findByHash(bundle.hash())).thenReturn(Optional.of(bundle));
        return bundle;
    }
}
