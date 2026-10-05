package com.ktogroup.ktoggle.sdkconnection.zout;

import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionPersistencePort;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SdkConnectionJpaAdapter implements SdkConnectionPersistencePort {

    private final SdkConnectionJpaRepository repository;

    @Override
    public Optional<SdkConnection> findByClientKey(String clientKey) {
        return repository.findByClientKey(clientKey).map(SdkConnectionJpaAdapter::toDomain);
    }

    @Override
    public List<SdkConnection> findAll() {
        return repository.findAll(Sort.by("name")).stream().map(SdkConnectionJpaAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByClientKey(String clientKey) {
        return repository.existsByClientKey(clientKey);
    }

    @Override
    public SdkConnection save(SdkConnection connection) {
        SdkConnectionEntity entity = repository.findByClientKey(connection.clientKey()).orElseGet(() -> {
            SdkConnectionEntity created = new SdkConnectionEntity();
            created.setId(Ids.newId());
            created.setClientKey(connection.clientKey());
            created.setEnvironmentKey(connection.environmentKey());
            created.setCreatedAt(connection.createdAt());
            return created;
        });
        entity.setName(connection.name());
        entity.setProjectKeys(connection.projectKeys());
        entity.setPinnedBundleHash(connection.pinnedBundleHash());
        entity.setUpdatedAt(connection.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    private static SdkConnection toDomain(SdkConnectionEntity e) {
        return new SdkConnection(e.getClientKey(), e.getName(), e.getEnvironmentKey(), List.copyOf(e.getProjectKeys()),
                e.getPinnedBundleHash(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
