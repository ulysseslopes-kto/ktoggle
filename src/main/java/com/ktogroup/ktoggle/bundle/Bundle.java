package com.ktogroup.ktoggle.bundle;

import java.time.Instant;

/**
 * Immutable, content-addressed bundle as stored. {@code content} is the RFC 8785 canonical JSON of a
 * {@link BundleBody}; {@code hash = sha256(content)}; {@code signature} signs the content bytes.
 * Signature, key id, creation time and author form the envelope and are outside the hash.
 */
public record Bundle(
        String hash,
        String contractVersion,
        String clientKey,
        String environmentKey,
        String content,
        String payloadHash,
        String signatureAlg,
        String keyId,
        String signature,
        Instant createdAt,
        String createdBy) {
}
