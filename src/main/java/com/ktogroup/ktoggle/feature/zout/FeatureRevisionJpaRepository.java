package com.ktogroup.ktoggle.feature.zout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureRevisionJpaRepository extends JpaRepository<FeatureRevisionEntity, UUID> {

    List<FeatureRevisionEntity> findByFeatureKeyOrderByRevisionDesc(String featureKey);

    Optional<FeatureRevisionEntity> findByFeatureKeyAndRevision(String featureKey, int revision);
}
