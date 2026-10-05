package com.ktogroup.ktoggle.project.zout;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {

    Optional<ProjectEntity> findByKey(String key);

    boolean existsByKey(String key);

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM feature WHERE project_key = :key)
                OR EXISTS (SELECT 1 FROM sdk_connection WHERE project_keys @> to_jsonb(ARRAY[CAST(:key AS text)]))""")
    boolean isReferenced(String key);
}
