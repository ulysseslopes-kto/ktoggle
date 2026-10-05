package com.ktogroup.ktoggle.attribute.zout;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeJpaRepository extends JpaRepository<AttributeEntity, UUID> {

    Optional<AttributeEntity> findByKey(String key);

    boolean existsByKey(String key);
}
