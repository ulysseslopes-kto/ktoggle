package com.ktogroup.ktoggle.savedgroup.zout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupPersistencePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SavedGroupJpaAdapter implements SavedGroupPersistencePort {

    private final SavedGroupJpaRepository repository;

    @Override
    public Optional<SavedGroup> findByKey(String key) {
        return repository.findByKey(key).map(SavedGroupJpaAdapter::toDomain);
    }

    @Override
    public List<SavedGroup> findAll() {
        return repository.findAll(Sort.by("key")).stream().map(SavedGroupJpaAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByKey(key);
    }

    @Override
    public SavedGroup save(SavedGroup group) {
        SavedGroupEntity entity = repository.findByKey(group.key()).orElseGet(() -> {
            SavedGroupEntity created = new SavedGroupEntity();
            created.setId(Ids.newId());
            created.setKey(group.key());
            created.setType(group.type());
            created.setCreatedAt(group.createdAt());
            return created;
        });
        entity.setName(group.name());
        entity.setDescription(group.description());
        entity.setAttributeKey(group.attributeKey());
        entity.setListValues(group.values() == null ? null : toArray(group.values()));
        entity.setCondition(group.condition());
        entity.setUpdatedAt(group.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public boolean isReferenced(String key) {
        return repository.isReferenced(key);
    }

    @Override
    public List<String> projectsUsing(String key) {
        return repository.projectsUsing(key);
    }

    @Override
    public void delete(String key) {
        repository.findByKey(key).ifPresent(repository::delete);
    }

    private static ArrayNode toArray(List<JsonNode> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private static SavedGroup toDomain(SavedGroupEntity e) {
        List<JsonNode> values = null;
        if (e.getListValues() != null) {
            values = new ArrayList<>();
            e.getListValues().forEach(values::add);
        }
        return new SavedGroup(e.getKey(), e.getName(), e.getDescription(), e.getType(), e.getAttributeKey(),
                values == null ? null : List.copyOf(values), e.getCondition(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getVersion());
    }
}
