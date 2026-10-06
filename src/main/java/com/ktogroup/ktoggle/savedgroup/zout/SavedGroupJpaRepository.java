package com.ktogroup.ktoggle.savedgroup.zout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SavedGroupJpaRepository extends JpaRepository<SavedGroupEntity, UUID> {

    Optional<SavedGroupEntity> findByKey(String key);

    boolean existsByKey(String key);

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (
                SELECT 1 FROM feature_environment fe, jsonb_array_elements(fe.rules) rule
                WHERE jsonb_exists(rule -> 'savedGroups', :key)
                   OR jsonb_exists(rule -> 'savedGroupsAny', :key)
                   OR jsonb_exists(rule -> 'savedGroupsNone', :key))""")
    boolean isReferenced(String key);

    @Query(nativeQuery = true, value = """
            SELECT DISTINCT f.project_key FROM feature f
            JOIN feature_environment fe ON fe.feature_id = f.id, jsonb_array_elements(fe.rules) rule
            WHERE NOT f.archived
              AND (jsonb_exists(rule -> 'savedGroups', :key)
                   OR jsonb_exists(rule -> 'savedGroupsAny', :key)
                   OR jsonb_exists(rule -> 'savedGroupsNone', :key))""")
    List<String> projectsUsing(String key);
}
