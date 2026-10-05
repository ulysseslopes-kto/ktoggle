package com.ktogroup.ktoggle.sdkconnection.zout;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SdkConnectionJpaRepository extends JpaRepository<SdkConnectionEntity, UUID> {

    Optional<SdkConnectionEntity> findByClientKey(String clientKey);

    boolean existsByClientKey(String clientKey);
}
