package com.ktogroup.ktoggle.attribute.zout;

import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributePersistencePort;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AttributeJpaAdapter implements AttributePersistencePort {

    private final AttributeJpaRepository repository;

    @Override
    public Optional<Attribute> findByKey(String key) {
        return repository.findByKey(key).map(AttributeJpaAdapter::toDomain);
    }

    @Override
    public List<Attribute> findAll() {
        return repository.findAll(Sort.by("key")).stream().map(AttributeJpaAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByKey(key);
    }

    @Override
    public Attribute save(Attribute attribute) {
        AttributeEntity entity = repository.findByKey(attribute.key()).orElseGet(() -> {
            AttributeEntity created = new AttributeEntity();
            created.setId(Ids.newId());
            created.setKey(attribute.key());
            created.setCreatedAt(attribute.createdAt());
            return created;
        });
        entity.setDatatype(attribute.datatype());
        entity.setDescription(attribute.description());
        entity.setHashAttribute(attribute.hashAttribute());
        entity.setPii(attribute.pii());
        entity.setEnumValues(attribute.enumValues().isEmpty() ? null : attribute.enumValues());
        entity.setArchived(attribute.archived());
        entity.setUpdatedAt(attribute.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    private static Attribute toDomain(AttributeEntity e) {
        return new Attribute(e.getKey(), e.getDatatype(), e.getDescription(), e.isHashAttribute(), e.isPii(),
                e.getEnumValues() == null ? List.of() : List.copyOf(e.getEnumValues()), e.isArchived(), e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion());
    }
}
