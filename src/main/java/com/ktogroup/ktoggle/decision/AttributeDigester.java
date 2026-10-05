package com.ktogroup.ktoggle.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Keyed digest of a full attribute set: {@code HMAC-SHA256(secret, JCS(attributes))}. Lets an auditor prove that a
 * claimed set of attributes is exactly the one an SDK evaluated, without ktoggle storing personal data in clear.
 */
@Slf4j
@Component
public class AttributeDigester {

    private final CanonicalJson canonicalJson;
    private final String keyId;
    private final byte[] secret;

    public AttributeDigester(CanonicalJson canonicalJson, DecisionProperties properties) {
        this.canonicalJson = canonicalJson;
        this.keyId = properties.digestKeyId();
        if (properties.digestSecret() == null || properties.digestSecret().isBlank()) {
            log.warn("ktoggle.decisions.digest-secret is not set: using an EPHEMERAL secret, attribute digests will not be "
                    + "verifiable after a restart");
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
        } else {
            this.secret = Base64.getDecoder().decode(properties.digestSecret());
        }
    }

    public String keyId() {
        return keyId;
    }

    /** Missing attributes digest like an empty set, the way ingestion and the evaluators treat them. */
    public String digest(JsonNode attributes) {
        JsonNode set = attributes == null || attributes.isNull() ? JsonNodeFactory.instance.objectNode() : attributes;
        return Hashes.hmacSha256Hex(secret, canonicalJson.canonicalize(set));
    }

    public boolean matches(JsonNode attributes, String digest, String digestKeyId) {
        return keyId.equals(digestKeyId) && Hashes.constantTimeEquals(digest(attributes), digest);
    }
}
