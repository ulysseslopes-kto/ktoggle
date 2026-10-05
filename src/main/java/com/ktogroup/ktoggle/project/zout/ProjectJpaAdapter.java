package com.ktogroup.ktoggle.project.zout;

import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.project.Project;
import com.ktogroup.ktoggle.project.ProjectPersistencePort;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectJpaAdapter implements ProjectPersistencePort {

    private final ProjectJpaRepository repository;

    @Override
    public Optional<Project> findByKey(String key) {
        return repository.findByKey(key).map(ProjectJpaAdapter::toDomain);
    }

    @Override
    public List<Project> findAll() {
        return repository.findAll(Sort.by("key")).stream().map(ProjectJpaAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByKey(key);
    }

    @Override
    public Project save(Project project) {
        ProjectEntity entity = repository.findByKey(project.key()).orElseGet(() -> {
            ProjectEntity created = new ProjectEntity();
            created.setId(Ids.newId());
            created.setKey(project.key());
            created.setCreatedAt(project.createdAt());
            return created;
        });
        entity.setName(project.name());
        entity.setDescription(project.description());
        entity.setUpdatedAt(project.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public boolean isReferenced(String key) {
        return repository.isReferenced(key);
    }

    @Override
    public void delete(String key) {
        repository.findByKey(key).ifPresent(repository::delete);
    }

    private static Project toDomain(ProjectEntity e) {
        return new Project(e.getKey(), e.getName(), e.getDescription(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
