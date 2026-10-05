package com.ktogroup.ktoggle.feature.zout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FeatureJpaRepository extends JpaRepository<FeatureEntity, UUID> {

    @Query("SELECT DISTINCT f FROM FeatureEntity f LEFT JOIN FETCH f.environments WHERE f.key = :key")
    Optional<FeatureEntity> findByKey(String key);

    boolean existsByKey(String key);

    @Query("""
            SELECT DISTINCT f FROM FeatureEntity f LEFT JOIN FETCH f.environments
            WHERE (:projectKey IS NULL OR f.projectKey = :projectKey)
              AND (:archived IS NULL OR f.archived = :archived)
              AND (:search IS NULL OR LOWER(f.key) LIKE :search OR LOWER(f.description) LIKE :search)
            ORDER BY f.key""")
    List<FeatureEntity> search(String projectKey, Boolean archived, String search);
}
