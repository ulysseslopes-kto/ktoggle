package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BundlePropertiesTest {

    @Test
    void everything_defaults_to_local_signing_and_no_archive() {
        BundleProperties properties = new BundleProperties(null, null, null);

        assertThat(properties.signing().provider()).isEqualTo("local");
        assertThat(properties.signing().localKeyId()).isEqualTo("local-dev");
        assertThat(properties.signing().trustedKeys()).isEmpty();
        assertThat(properties.archive().s3().enabled()).isFalse();
        assertThat(properties.archive().s3().prefix()).isEqualTo("bundles/");
        assertThat(properties.archive().s3().retentionDays()).isEqualTo(1825);
        assertThat(properties.reconcileInterval()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void blank_signing_values_fall_back_to_defaults() {
        BundleProperties.Signing signing = new BundleProperties.Signing(" ", "key", "priv", "pub", " ", null);

        assertThat(signing.provider()).isEqualTo("local");
        assertThat(signing.localKeyId()).isEqualTo("local-dev");
        assertThat(signing.kmsKeyId()).isEqualTo("key");
    }

    @Test
    void configured_signing_values_are_kept_and_trusted_keys_are_copied() {
        BundleProperties.Signing signing = new BundleProperties.Signing("kms", "arn:aws:kms:1", null, null, "k-2", Map.of("old", "AAA"));

        assertThat(signing.provider()).isEqualTo("kms");
        assertThat(signing.localKeyId()).isEqualTo("k-2");
        assertThat(signing.trustedKeys()).containsEntry("old", "AAA");
    }

    @Test
    void s3_archive_applies_defaults_only_to_unset_values() {
        BundleProperties.S3 defaults = new BundleProperties.S3(true, "bucket", null, 0);
        BundleProperties.S3 custom = new BundleProperties.S3(true, "bucket", "audit/", 30);

        assertThat(defaults.prefix()).isEqualTo("bundles/");
        assertThat(defaults.retentionDays()).isEqualTo(1825);
        assertThat(custom.prefix()).isEqualTo("audit/");
        assertThat(custom.retentionDays()).isEqualTo(30);
    }
}
