package com.ktogroup.ktoggle.ztest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleBody;
import com.ktogroup.ktoggle.bundle.BundleCodec;
import com.ktogroup.ktoggle.bundle.Evaluators;
import com.ktogroup.ktoggle.bundle.signing.EcKeys;
import com.ktogroup.ktoggle.bundle.signing.LocalBundleSigner;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real, signed bundles for unit tests of the modules that consume them. */
public final class TestBundles {

    public static final Instant CREATED_AT = Instant.parse("2026-10-05T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CanonicalJson canonicalJson = new CanonicalJson(objectMapper);
    private final KeyPair keys = EcKeys.generate();
    private final LocalBundleSigner signer = new LocalBundleSigner("test-key", keys.getPrivate(), keys.getPublic());
    private final BundleCodec codec = new BundleCodec(canonicalJson, signer,
            keyId -> "test-key".equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty(), objectMapper);

    public BundleCodec codec() {
        return codec;
    }

    public CanonicalJson canonicalJson() {
        return canonicalJson;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public BundleBody body(String clientKey, boolean featureDefault) {
        ObjectNode features = JsonNodeFactory.instance.objectNode();
        features.putObject("checkout").put("defaultValue", featureDefault);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("features", features);
        return new BundleBody(BundleBody.CONTRACT_VERSION, new BundleBody.Target(clientKey, "prod", List.of()),
                Evaluators.current(), Map.of("checkout", 1), payload);
    }

    public Bundle bundle(String clientKey, boolean featureDefault) {
        return codec.seal(body(clientKey, featureDefault), CREATED_AT, "alice");
    }

    /** A bundle built with {@code featureDefault = true} whose content was altered after signing: its hash no longer matches. */
    public Bundle tampered(Bundle bundle) {
        return new Bundle(bundle.hash(), bundle.contractVersion(), bundle.clientKey(), bundle.environmentKey(),
                bundle.content().replace("\"defaultValue\":true", "\"defaultValue\":false"),
                bundle.payloadHash(), bundle.signatureAlg(), bundle.keyId(), bundle.signature(), bundle.createdAt(), bundle.createdBy());
    }

    public static BundleActivation activation(String clientKey, long position, String bundleHash, String prevHash) {
        return new BundleActivation(Ids.newId(), clientKey, position, bundleHash, BundleActivation.Kind.PUBLISH, Ids.newId(), "alice",
                CREATED_AT.plusSeconds(position), null, prevHash, "hash-" + position);
    }
}
