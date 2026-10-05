package com.ktogroup.ktoggle.bundle.signing.zout;

import com.ktogroup.ktoggle.bundle.signing.BundleSigner;
import com.ktogroup.ktoggle.bundle.signing.EcKeys;
import com.ktogroup.ktoggle.bundle.signing.TrustedKeys;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/**
 * Signs with an AWS KMS asymmetric key (key spec {@code ECC_NIST_P256}); the private key never leaves KMS.
 * KMS signs the SHA-256 digest, which is equivalent to {@code SHA256withECDSA} over the content, so signatures
 * verify locally with the public key. Public keys of any KMS key id are fetched once and cached, which keeps
 * bundles signed before a key rotation verifiable.
 */
public class KmsBundleSigner implements BundleSigner, TrustedKeys {

    private final KmsClient kms;
    private final String keyId;
    private final Map<String, PublicKey> publicKeys = new ConcurrentHashMap<>();

    public KmsBundleSigner(KmsClient kms, String keyId) {
        this.kms = kms;
        this.keyId = keyId;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String sign(byte[] content) {
        byte[] signature = kms.sign(request -> request
                        .keyId(keyId)
                        .message(SdkBytes.fromByteArray(Hashes.sha256(content)))
                        .messageType(MessageType.DIGEST)
                        .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256))
                .signature().asByteArray();
        return Base64.getEncoder().encodeToString(signature);
    }

    @Override
    public Optional<PublicKey> find(String requestedKeyId) {
        if (!requestedKeyId.startsWith("arn:aws:kms:") && !requestedKeyId.equals(keyId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(publicKeys.computeIfAbsent(requestedKeyId, id ->
                    EcKeys.publicKey(kms.getPublicKey(r -> r.keyId(id)).publicKey().asByteArray())));
        } catch (KmsException e) {
            return Optional.empty();
        }
    }
}
