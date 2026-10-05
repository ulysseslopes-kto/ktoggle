package com.ktogroup.ktoggle.bundle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.bundle.signing.BundleSigner;
import com.ktogroup.ktoggle.bundle.signing.EcKeys;
import com.ktogroup.ktoggle.bundle.signing.TrustedKeys;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import com.ktogroup.ktoggle.commons.exception.IntegrityException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds (canonicalize, hash, sign) and verifies (re-canonicalize, re-hash, check signature) bundles. */
@Component
@RequiredArgsConstructor
public class BundleCodec {

    private final CanonicalJson canonicalJson;
    private final BundleSigner signer;
    private final TrustedKeys trustedKeys;
    private final ObjectMapper objectMapper;

    public Bundle seal(BundleBody body, Instant createdAt, String createdBy) {
        String content = canonicalJson.canonicalize(body);
        String hash = Hashes.sha256Hex(content);
        String signature = signer.sign(content.getBytes(StandardCharsets.UTF_8));
        return new Bundle(hash, body.contractVersion(), body.target().clientKey(), body.target().environment(), content,
                payloadHash(body), BundleSigner.ALGORITHM, signer.keyId(), signature, createdAt, createdBy);
    }

    public String payloadHash(BundleBody body) {
        return Hashes.sha256Hex(canonicalJson.canonicalize(body.payload()));
    }

    /**
     * Full integrity check of a stored bundle. Never trust the stored hash: it is recomputed from the content,
     * and the content itself must still be canonical and signed by a trusted key.
     *
     * @throws IntegrityException on any mismatch
     */
    public BundleBody verify(Bundle bundle) {
        if (!BundleBody.CONTRACT_VERSION.equals(bundle.contractVersion())) {
            throw new IntegrityException("Bundle %s has unsupported contract %s".formatted(bundle.hash(), bundle.contractVersion()));
        }
        if (!canonicalJson.isCanonical(bundle.content())) {
            throw new IntegrityException("Bundle %s content is not canonical".formatted(bundle.hash()));
        }
        if (!Hashes.constantTimeEquals(Hashes.sha256Hex(bundle.content()), bundle.hash())) {
            throw new IntegrityException("Bundle %s content does not match its hash".formatted(bundle.hash()));
        }
        PublicKey key = trustedKeys.find(bundle.keyId())
                .orElseThrow(() -> new IntegrityException("Bundle %s signed by untrusted key %s".formatted(bundle.hash(), bundle.keyId())));
        if (!BundleSigner.ALGORITHM.equals(bundle.signatureAlg())
                || !EcKeys.verify(key, bundle.content().getBytes(StandardCharsets.UTF_8), bundle.signature())) {
            throw new IntegrityException("Bundle %s has an invalid signature".formatted(bundle.hash()));
        }
        BundleBody body = parse(bundle);
        if (!body.target().clientKey().equals(bundle.clientKey())) {
            throw new IntegrityException("Bundle %s envelope does not match its body".formatted(bundle.hash()));
        }
        return body;
    }

    public BundleBody parse(Bundle bundle) {
        try {
            return objectMapper.readValue(bundle.content(), BundleBody.class);
        } catch (JsonProcessingException e) {
            throw new IntegrityException("Bundle %s content is not a valid %s".formatted(bundle.hash(), BundleBody.CONTRACT_VERSION));
        }
    }
}
