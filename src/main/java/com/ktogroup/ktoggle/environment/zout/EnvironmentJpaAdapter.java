package com.ktogroup.ktoggle.environment.zout;

import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.Environment;
import com.ktogroup.ktoggle.environment.EnvironmentPersistencePort;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EnvironmentJpaAdapter implements EnvironmentPersistencePort {

    private final EnvironmentJpaRepository repository;

    @Override
    public Optional<Environment> findByKey(String key) {
        return repository.findByKey(key).map(EnvironmentJpaAdapter::toDomain);
    }

    @Override
    public List<Environment> findAll() {
        return repository.findAll(Sort.by("sortOrder", "key")).stream().map(EnvironmentJpaAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByKey(key);
    }

    @Override
    public Environment save(Environment environment) {
        EnvironmentEntity entity = repository.findByKey(environment.key()).orElseGet(() -> {
            EnvironmentEntity created = new EnvironmentEntity();
            created.setId(Ids.newId());
            created.setKey(environment.key());
            created.setCreatedAt(environment.createdAt());
            return created;
        });
        entity.setName(environment.name());
        entity.setDescription(environment.description());
        entity.setSortOrder(environment.sortOrder());
        entity.setRequiresReview(environment.requiresReview());
        entity.setUpdatedAt(environment.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public boolean isInUse(String key) {
        return repository.isInUse(key);
    }

    @Override
    public void delete(String key) {
        repository.deleteFeatureSettings(key);
        repository.findByKey(key).ifPresent(repository::delete);
    }

    private static Environment toDomain(EnvironmentEntity e) {
        return new Environment(e.getKey(), e.getName(), e.getDescription(), e.getSortOrder(), e.isRequiresReview(), e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion());
    }
}
