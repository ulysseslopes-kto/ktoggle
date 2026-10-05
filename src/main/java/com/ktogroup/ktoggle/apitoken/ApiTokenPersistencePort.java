package com.ktogroup.ktoggle.apitoken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenPersistencePort {

    void insert(ApiToken token);

    Optional<ApiToken> findById(UUID id);

    Optional<ApiToken> findByHash(String tokenHash);

    boolean existsActiveByName(String name);

    List<ApiToken> findAll();

    void revoke(UUID id, String revokedBy, Instant revokedAt);

    void touch(UUID id, Instant lastUsedAt);
}
