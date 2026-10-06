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

    /**
     * Projects of the non-archived features with a rule referencing the group (null for features without a project):
     * the features whose payload changes when the group changes.
     */
    List<String> projectsUsing(String key);

    void delete(String key);
}
