package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.commons.exception.IntegrityException;
import com.ktogroup.ktoggle.ztest.TestBundles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class BundleArchiverTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final TestBundles testBundles = new TestBundles();
    private final BundlePersistencePort persistence = mock(BundlePersistencePort.class);
    private final BundleArchive archive = mock(BundleArchive.class);
    private final BundleCodec codec = mock(BundleCodec.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BundleArchiver archiver = new BundleArchiver(persistence, archive, codec, meters, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void pending_bundles_are_verified_then_archived_then_marked() {
        Bundle bundle = testBundles.bundle("sdk-a", true);
        when(persistence.findNotArchived(100)).thenReturn(List.of(bundle));
        when(archive.store(bundle)).thenReturn("s3://bucket/bundles/" + bundle.hash() + ".json");

        archiver.archivePending();

        InOrder order = inOrder(codec, archive, persistence);
        order.verify(codec).verify(bundle);
        order.verify(archive).store(bundle);
        order.verify(persistence).markArchived(bundle.hash(), "s3://bucket/bundles/" + bundle.hash() + ".json", NOW);
        assertThat(meters.get("ktoggle.bundle.archived").counter().count()).isEqualTo(1);
    }

    @Test
    void a_bundle_that_fails_verification_is_never_sent_to_the_archive() {
        Bundle forged = testBundles.tampered(testBundles.bundle("sdk-a", true));
        when(persistence.findNotArchived(100)).thenReturn(List.of(forged));
        when(codec.verify(forged)).thenThrow(new IntegrityException("hash mismatch"));

        archiver.archivePending();

        verify(archive, never()).store(any());
        verify(persistence, never()).markArchived(any(), any(), any());
        assertThat(meters.get("ktoggle.bundle.archive_failures").counter().count()).isEqualTo(1);
    }

    @Test
    void a_failing_upload_is_counted_and_does_not_stop_the_other_bundles() {
        Bundle failing = testBundles.bundle("sdk-a", true);
        Bundle fine = testBundles.bundle("sdk-b", true);
        when(persistence.findNotArchived(100)).thenReturn(List.of(failing, fine));
        when(archive.store(failing)).thenThrow(new IllegalStateException("S3 unavailable"));
        when(archive.store(fine)).thenReturn("s3://bucket/fine");

        archiver.archivePending();

        verify(persistence, never()).markArchived(failing.hash(), "s3://bucket/fine", NOW);
        verify(persistence).markArchived(fine.hash(), "s3://bucket/fine", NOW);
        assertThat(meters.get("ktoggle.bundle.archive_failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("ktoggle.bundle.archived").counter().count()).isEqualTo(1);
    }

    @Test
    void nothing_pending_means_nothing_to_do() {
        when(persistence.findNotArchived(100)).thenReturn(List.of());

        archiver.archivePending();

        verify(archive, never()).store(any());
    }
}
