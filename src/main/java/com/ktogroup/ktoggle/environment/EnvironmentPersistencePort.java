package com.ktogroup.ktoggle.environment;

import java.util.List;
import java.util.Optional;

public interface EnvironmentPersistencePort {

    Optional<Environment> findByKey(String key);

    List<Environment> findAll();

    boolean existsByKey(String key);

    Environment save(Environment environment);

    /** True if an SDK connection uses it or a feature is enabled in it. */
    boolean isInUse(String key);

    /** Deletes the environment and every (disabled) per-feature setting attached to it. */
    void delete(String key);
}
