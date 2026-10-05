package com.ktogroup.ktoggle.sdkconnection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import java.time.Instant;
import java.util.List;
import lombok.With;

/**
 * What an SDK client key gets: the features of one environment, optionally restricted to some projects.
 *
 * @param pinnedBundleHash when set (emergency rollback), automatic publication is suspended for this connection
 * @param encryptPayload   serve {@code encryptedFeatures} (AES-128-CBC) instead of clear-text features; SDKs need the key
 * @param decryptionKey    base64 AES key given to this connection's SDKs; never serialized (admins read it explicitly)
 */
@With
public record SdkConnection(String clientKey, String name, String environmentKey, List<String> projectKeys,
                            String pinnedBundleHash, boolean encryptPayload, @JsonIgnore String decryptionKey,
                            Instant createdAt, Instant updatedAt, Long version) {

    public SdkConnection(String clientKey, String name, String environmentKey, List<String> projectKeys,
                         String pinnedBundleHash, Instant createdAt, Instant updatedAt, Long version) {
        this(clientKey, name, environmentKey, projectKeys, pinnedBundleHash, false, null, createdAt, updatedAt, version);
    }

    public boolean includesProject(String projectKey) {
        return projectKeys.isEmpty() || (projectKey != null && projectKeys.contains(projectKey));
    }

    /** Identifies the key without revealing it (audit trail, UI), e.g. to show that a key was rotated. */
    @JsonProperty("keyFingerprint")
    public String keyFingerprint() {
        return decryptionKey == null ? null : Hashes.sha256Hex(decryptionKey).substring(0, 12);
    }

    /** What the delivery layer must render: clear text, or encrypted with a given key. */
    @JsonIgnore
    public String deliveryMode() {
        return encryptPayload && decryptionKey != null ? "aes:" + keyFingerprint() : "plain";
    }
}
