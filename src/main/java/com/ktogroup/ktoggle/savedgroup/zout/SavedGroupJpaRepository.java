package com.ktogroup.ktoggle.savedgroup.zout;

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
                WHERE jsonb_exists(rule -> 'savedGroups', :key))""")
    boolean isReferenced(String key);
}
