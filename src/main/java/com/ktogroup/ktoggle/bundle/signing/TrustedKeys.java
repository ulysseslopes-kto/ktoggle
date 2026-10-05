package com.ktogroup.ktoggle.bundle.signing;

import java.security.PublicKey;
import java.util.Optional;

/** Public keys accepted when verifying bundle signatures, by key id. */
@FunctionalInterface
public interface TrustedKeys {

    Optional<PublicKey> find(String keyId);
}
