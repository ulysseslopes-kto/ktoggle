package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.signing.EcKeys;
import com.ktogroup.ktoggle.bundle.signing.LocalBundleSigner;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.exception.IntegrityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BundleCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CanonicalJson canonicalJson = new CanonicalJson(objectMapper);
    private final KeyPair keys = EcKeys.generate();
    private final LocalBundleSigner signer = new LocalBundleSigner("test-key", keys.getPrivate(), keys.getPublic());
    private final BundleCodec codec = new BundleCodec(canonicalJson, signer,
            keyId -> "test-key".equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty(), objectMapper);

    @Test
    void sealed_bundle_verifies_and_round_trips() {
        Bundle bundle = codec.seal(body(true), Instant.parse("2026-10-05T12:00:00Z"), "alice");

        BundleBody verified = codec.verify(bundle);

        assertThat(bundle.hash()).hasSize(64);
        assertThat(verified.payload()).isEqualTo(body(true).payload());
        assertThat(verified.sources()).containsEntry("checkout", 7);
    }

    @Test
    void hash_depends_on_content_only_not_on_envelope() {
        Bundle first = codec.seal(body(true), Instant.parse("2026-01-01T00:00:00Z"), "alice");
        Bundle second = codec.seal(body(true), Instant.parse("2026-12-31T23:59:59Z"), "bob");
        Bundle different = codec.seal(body(false), Instant.parse("2026-01-01T00:00:00Z"), "alice");

        assertThat(second.hash()).isEqualTo(first.hash());
        assertThat(different.hash()).isNotEqualTo(first.hash());
        assertThat(different.payloadHash()).isNotEqualTo(first.payloadHash());
    }

    @Test
    void tampered_content_is_rejected_even_if_the_hash_is_recomputed() {
        Bundle original = codec.seal(body(true), Instant.now(), "alice");
        String forgedContent = original.content().replace("\"force\":true", "\"force\":false");
        Bundle forged = new Bundle(com.ktogroup.ktoggle.commons.canonical.Hashes.sha256Hex(forgedContent),
                original.contractVersion(), original.clientKey(), original.environmentKey(), forgedContent,
                original.payloadHash(), original.signatureAlg(), original.keyId(), original.signature(),
                original.createdAt(), original.createdBy());

        assertThatThrownBy(() -> codec.verify(forged)).isInstanceOf(IntegrityException.class).hasMessageContaining("signature");
    }

    @Test
    void stored_hash_is_never_trusted() {
        Bundle original = codec.seal(body(true), Instant.now(), "alice");
        Bundle wrongHash = new Bundle("0".repeat(64), original.contractVersion(), original.clientKey(),
                original.environmentKey(), original.content(), original.payloadHash(), original.signatureAlg(),
                original.keyId(), original.signature(), original.createdAt(), original.createdBy());

        assertThatThrownBy(() -> codec.verify(wrongHash)).isInstanceOf(IntegrityException.class).hasMessageContaining("hash");
    }

    @Test
    void untrusted_key_and_non_canonical_content_are_rejected() {
        Bundle original = codec.seal(body(true), Instant.now(), "alice");
        Bundle otherKey = new Bundle(original.hash(), original.contractVersion(), original.clientKey(),
                original.environmentKey(), original.content(), original.payloadHash(), original.signatureAlg(),
                "rogue-key", original.signature(), original.createdAt(), original.createdBy());
        Bundle reformatted = new Bundle(original.hash(), original.contractVersion(), original.clientKey(),
                original.environmentKey(), original.content().replace(",", ", "), original.payloadHash(),
                original.signatureAlg(), original.keyId(), original.signature(), original.createdAt(), original.createdBy());

        assertThatThrownBy(() -> codec.verify(otherKey)).isInstanceOf(IntegrityException.class).hasMessageContaining("untrusted");
        assertThatThrownBy(() -> codec.verify(reformatted)).isInstanceOf(IntegrityException.class).hasMessageContaining("canonical");
    }

    private BundleBody body(boolean value) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode rule = payload.putObject("features").putObject("checkout").put("defaultValue", false)
                .putArray("rules").addObject();
        rule.put("id", "fr_1");
        rule.put("force", value);
        return new BundleBody(BundleBody.CONTRACT_VERSION, new BundleBody.Target("sdk-abc12345", "prd", List.of()),
                Evaluators.current(), Map.of("checkout", 7), payload);
    }
}
