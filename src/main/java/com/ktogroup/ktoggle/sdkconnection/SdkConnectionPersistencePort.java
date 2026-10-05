package com.ktogroup.ktoggle.sdkconnection;

import java.util.List;
import java.util.Optional;

public interface SdkConnectionPersistencePort {

    Optional<SdkConnection> findByClientKey(String clientKey);

    List<SdkConnection> findAll();

    boolean existsByClientKey(String clientKey);

    SdkConnection save(SdkConnection connection);
}
