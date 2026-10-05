package com.ktogroup.ktoggle.bundle.signing;

import java.security.PrivateKey;
import java.security.PublicKey;

/** Signs with key material held by the process. For local development and tests; stg/prd use KMS. */
public class LocalBundleSigner implements BundleSigner {

    private final String keyId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public LocalBundleSigner(String keyId, PrivateKey privateKey, PublicKey publicKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String sign(byte[] content) {
        return EcKeys.sign(privateKey, content);
    }

    public PublicKey publicKey() {
        return publicKey;
    }
}
