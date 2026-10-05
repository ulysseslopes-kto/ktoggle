package com.ktogroup.ktoggle.bundle.signing;

/** Signs canonical bundle content. Signatures are ECDSA P-256 over SHA-256, DER-encoded, base64. */
public interface BundleSigner {

    String ALGORITHM = "ECDSA_P256_SHA256";

    String keyId();

    String sign(byte[] content);
}
