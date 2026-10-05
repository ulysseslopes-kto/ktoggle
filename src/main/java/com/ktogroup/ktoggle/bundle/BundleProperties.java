package com.ktogroup.ktoggle.bundle;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ktoggle.bundle")
public record BundleProperties(Signing signing, Archive archive, Duration reconcileInterval) {

    public BundleProperties {
        signing = signing == null ? new Signing(null, null, null, null, null, null) : signing;
        archive = archive == null ? new Archive(null) : archive;
        reconcileInterval = reconcileInterval == null ? Duration.ofMinutes(1) : reconcileInterval;
    }

    /**
     * @param provider        {@code local} (key material in config) or {@code kms} (AWS KMS asymmetric key)
     * @param trustedKeys     additional keyId &rarr; public key (base64 X.509) accepted for verification, e.g. after a
     *                        key rotation, so old bundles stay verifiable
     */
    public record Signing(String provider, String kmsKeyId, String localPrivateKey, String localPublicKey,
                          String localKeyId, Map<String, String> trustedKeys) {

        public Signing {
            provider = provider == null || provider.isBlank() ? "local" : provider;
            localKeyId = localKeyId == null || localKeyId.isBlank() ? "local-dev" : localKeyId;
            trustedKeys = trustedKeys == null ? Map.of() : Map.copyOf(trustedKeys);
        }
    }

    public record Archive(S3 s3) {

        public Archive {
            s3 = s3 == null ? new S3(false, null, null, 0) : s3;
        }
    }

    /** WORM copy of every bundle in an S3 bucket with Object Lock (COMPLIANCE mode). */
    public record S3(boolean enabled, String bucket, String prefix, int retentionDays) {

        public S3 {
            prefix = prefix == null ? "bundles/" : prefix;
            retentionDays = retentionDays <= 0 ? 1825 : retentionDays;
        }
    }
}
