package com.ktogroup.ktoggle.savedgroup;

import java.util.List;
import java.util.Optional;

public interface SavedGroupPersistencePort {

    Optional<SavedGroup> findByKey(String key);

    List<SavedGroup> findAll();

    boolean existsByKey(String key);

    SavedGroup save(SavedGroup group);

    /** True if any feature rule (in any environment) references the group. */
    boolean isReferenced(String key);

    void delete(String key);
}
