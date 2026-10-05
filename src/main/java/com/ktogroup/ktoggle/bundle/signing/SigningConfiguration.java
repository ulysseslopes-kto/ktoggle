package com.ktogroup.ktoggle.bundle.signing;

import com.ktogroup.ktoggle.bundle.BundleProperties;
import com.ktogroup.ktoggle.bundle.signing.zout.KmsBundleSigner;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

@Slf4j
@Configuration
public class SigningConfiguration {

    @Bean
    public BundleSigner bundleSigner(BundleProperties properties, ObjectProvider<KmsClient> kmsClient) {
        BundleProperties.Signing signing = properties.signing();
        if ("kms".equals(signing.provider())) {
            if (signing.kmsKeyId() == null || signing.kmsKeyId().isBlank()) {
                throw new IllegalStateException("ktoggle.bundle.signing.kms-key-id is required with provider=kms");
            }
            return new KmsBundleSigner(kmsClient.getObject(), signing.kmsKeyId());
        }
        if (signing.localPrivateKey() == null || signing.localPrivateKey().isBlank()) {
            log.warn("No bundle signing key configured: using an EPHEMERAL key. Bundles signed now will not verify "
                    + "after a restart. Configure ktoggle.bundle.signing.* (local) or use provider=kms.");
            KeyPair pair = EcKeys.generate();
            return new LocalBundleSigner("ephemeral-" + Integer.toHexString(pair.getPublic().hashCode()),
                    pair.getPrivate(), pair.getPublic());
        }
        return new LocalBundleSigner(signing.localKeyId(), EcKeys.privateKey(signing.localPrivateKey()),
                EcKeys.publicKey(signing.localPublicKey()));
    }

    /** Signer's own key + statically configured keys (rotation) + any KMS key (looked up on demand). */
    @Bean
    public TrustedKeys trustedKeys(BundleSigner signer, BundleProperties properties) {
        Map<String, PublicKey> configured = new HashMap<>();
        properties.signing().trustedKeys().forEach((id, key) -> configured.put(id, EcKeys.publicKey(key)));
        if (signer instanceof LocalBundleSigner local) {
            configured.put(local.keyId(), local.publicKey());
        }
        TrustedKeys dynamic = signer instanceof KmsBundleSigner kms ? kms : keyId -> Optional.empty();
        return keyId -> Optional.ofNullable(configured.get(keyId)).or(() -> dynamic.find(keyId));
    }
}
