package com.ktogroup.ktoggle.feature.zout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort;
import com.ktogroup.ktoggle.feature.FeatureRevision;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.Prerequisite;
import com.ktogroup.ktoggle.feature.Rule;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeatureJpaAdapter implements FeaturePersistencePort {

    private static final TypeReference<List<Rule>> RULES = new TypeReference<>() {
    };
    private static final TypeReference<List<Prerequisite>> PREREQUISITES = new TypeReference<>() {
    };

    private final FeatureJpaRepository repository;
    private final FeatureRevisionJpaRepository revisionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Feature> findByKey(String key) {
        return repository.findByKey(key).map(this::toDomain);
    }

    @Override
    public List<Feature> findAll(FeatureFilter filter) {
        String search = filter.search() == null || filter.search().isBlank()
                ? null : "%" + filter.search().toLowerCase(Locale.ROOT).strip() + "%";
        return repository.search(filter.projectKey(), filter.archived(), search).stream()
                .filter(f -> filter.tag() == null || f.getTags().contains(filter.tag()))
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Feature> findAllActive() {
        return repository.search(null, false, null).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByKey(key);
    }

    @Override
    public Feature save(Feature feature) {
        FeatureEntity entity = repository.findByKey(feature.key()).orElseGet(() -> {
            FeatureEntity created = new FeatureEntity();
            created.setId(Ids.newId());
            created.setKey(feature.key());
            created.setValueType(feature.valueType());
            created.setCreatedAt(feature.createdAt());
            created.setCreatedBy(feature.createdBy());
            return created;
        });
        entity.setProjectKey(feature.projectKey());
        entity.setDefaultValue(feature.defaultValue());
        entity.setDescription(feature.description());
        entity.setOwner(feature.owner());
        entity.setTags(feature.tags());
        entity.setArchived(feature.archived());
        entity.setPrerequisites(objectMapper.valueToTree(feature.prerequisites()));
        entity.setRevision(feature.revision());
        entity.setUpdatedAt(feature.updatedAt());
        entity.setUpdatedBy(feature.updatedBy());
        syncEnvironments(entity, feature.environments());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public void saveRevision(FeatureRevision revision) {
        FeatureEntity feature = repository.findByKey(revision.featureKey()).orElseThrow();
        FeatureRevisionEntity entity = new FeatureRevisionEntity();
        entity.setId(Ids.newId());
        entity.setFeatureId(feature.getId());
        entity.setFeatureKey(revision.featureKey());
        entity.setRevision(revision.revision());
        entity.setSnapshot(objectMapper.valueToTree(revision.snapshot()));
        entity.setChangeId(revision.changeId());
        entity.setComment(revision.comment());
        entity.setCreatedBy(revision.createdBy());
        entity.setCreatedAt(revision.createdAt());
        revisionRepository.save(entity);
    }

    @Override
    public List<FeatureRevision> findRevisions(String featureKey) {
        return revisionRepository.findByFeatureKeyOrderByRevisionDesc(featureKey).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<FeatureRevision> findRevision(String featureKey, int revision) {
        return revisionRepository.findByFeatureKeyAndRevision(featureKey, revision).map(this::toDomain);
    }

    private void syncEnvironments(FeatureEntity entity, Map<String, EnvironmentSettings> environments) {
        entity.getEnvironments().removeIf(env -> !environments.containsKey(env.getEnvironmentKey()));
        environments.forEach((key, settings) -> {
            FeatureEnvironmentEntity env = entity.getEnvironments().stream()
                    .filter(e -> e.getEnvironmentKey().equals(key))
                    .findFirst()
                    .orElseGet(() -> {
                        FeatureEnvironmentEntity created = new FeatureEnvironmentEntity();
                        created.setId(Ids.newId());
                        created.setFeature(entity);
                        created.setEnvironmentKey(key);
                        entity.getEnvironments().add(created);
                        return created;
                    });
            env.setEnabled(settings.enabled());
            env.setRules(rulesJson(settings.rules()));
        });
    }

    private Feature toDomain(FeatureEntity e) {
        Map<String, EnvironmentSettings> environments = new HashMap<>();
        e.getEnvironments().forEach(env ->
                environments.put(env.getEnvironmentKey(), new EnvironmentSettings(env.isEnabled(), rules(env.getRules()))));
        return new Feature(e.getKey(), e.getProjectKey(), e.getValueType(), e.getDefaultValue(), e.getDescription(),
                e.getOwner(), e.getTags(), e.isArchived(), prerequisites(e.getPrerequisites()), environments, e.getRevision(),
                e.getCreatedAt(),
                e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy(), e.getVersion());
    }

    private FeatureRevision toDomain(FeatureRevisionEntity e) {
        try {
            FeatureSnapshot snapshot = objectMapper.treeToValue(e.getSnapshot(), FeatureSnapshot.class);
            return new FeatureRevision(e.getFeatureKey(), e.getRevision(), snapshot, e.getChangeId(), e.getComment(),
                    e.getCreatedBy(), e.getCreatedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupted snapshot for revision %d of %s".formatted(e.getRevision(), e.getFeatureKey()), ex);
        }
    }

    /** Written through the declared {@code List<Rule>} type so each element keeps its polymorphic {@code type}. */
    private JsonNode rulesJson(List<Rule> rules) {
        try {
            return objectMapper.readTree(objectMapper.writerFor(RULES).writeValueAsString(rules));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize rules", ex);
        }
    }

    private List<Rule> rules(JsonNode json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readerFor(RULES).readValue(json);
        } catch (IOException ex) {
            throw new IllegalStateException("Corrupted rules JSON", ex);
        }
    }

    private List<Prerequisite> prerequisites(JsonNode json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readerFor(PREREQUISITES).readValue(json);
        } catch (IOException ex) {
            throw new IllegalStateException("Corrupted prerequisites JSON", ex);
        }
    }
}
