package com.ktogroup.ktoggle.environment.zout;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EnvironmentJpaRepository extends JpaRepository<EnvironmentEntity, UUID> {

    Optional<EnvironmentEntity> findByKey(String key);

    boolean existsByKey(String key);

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM sdk_connection WHERE environment_key = :key)
                OR EXISTS (SELECT 1 FROM feature_environment WHERE environment_key = :key AND enabled)""")
    boolean isInUse(String key);

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM feature_environment WHERE environment_key = :key")
    void deleteFeatureSettings(String key);
}
