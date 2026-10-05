package com.ktogroup.ktoggle.apitoken;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;
import lombok.With;

/**
 * A long-lived credential for automation. The secret itself is never stored: {@code tokenHash} is its SHA-256, and
 * {@code prefix} (the first characters of the secret) lets people recognize a token in lists and logs.
 */
@With
public record ApiToken(UUID id, String name, String prefix, @JsonIgnore String tokenHash, ApiTokenRole role,
                       String createdBy, Instant createdAt, Instant expiresAt, Instant lastUsedAt, String revokedBy,
                       Instant revokedAt) {

    public boolean activeAt(Instant instant) {
        return revokedAt == null && (expiresAt == null || instant.isBefore(expiresAt));
    }

    /** Actor recorded in the audit trail for everything done with this token. */
    public String actor() {
        return "token:" + name;
    }
}
