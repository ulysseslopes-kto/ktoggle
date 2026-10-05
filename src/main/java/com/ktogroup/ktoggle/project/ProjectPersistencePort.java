package com.ktogroup.ktoggle.project;

import java.util.List;
import java.util.Optional;

public interface ProjectPersistencePort {

    Optional<Project> findByKey(String key);

    List<Project> findAll();

    boolean existsByKey(String key);

    /** Inserts or updates by key; returns the persisted state (with the new version). */
    Project save(Project project);

    /** True if any feature or SDK connection points to this project. */
    boolean isReferenced(String key);

    void delete(String key);
}
