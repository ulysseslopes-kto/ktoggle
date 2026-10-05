package com.ktogroup.ktoggle.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.Keys;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort.FeatureFilter;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every mutation goes through {@link #commit}: one new revision snapshot, one audit entry and one
 * {@link ConfigurationChangedEvent}, all in the same transaction as the change. Features are never deleted —
 * revisions and bundles reference them — they are archived.
 */
@Service
@RequiredArgsConstructor
public class FeatureService {

    private static final String ENTITY = "Feature";

    private final FeaturePersistencePort persistence;
    private final RuleValidator ruleValidator;
    private final PrerequisiteValidator prerequisiteValidator;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final AttributeService attributeService;
    private final SavedGroupService savedGroupService;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Feature> findAll(FeatureFilter filter) {
        return persistence.findAll(filter);
    }

    @Transactional(readOnly = true)
    public List<Feature> findAllActive() {
        return persistence.findAllActive();
    }

    /** Active features that depend on {@code key}, at feature level or in any rule — what turning it off affects. */
    @Transactional(readOnly = true)
    public List<Dependent> dependents(String key) {
        get(key);
        return persistence.findAllActive().stream()
                .filter(feature -> PrerequisiteValidator.parents(feature.snapshot()).contains(key))
                .map(feature -> new Dependent(feature.key(),
                        feature.prerequisites().stream().anyMatch(p -> p.featureKey().equals(key)),
                        feature.environments().entrySet().stream()
                                .filter(e -> e.getValue().rules().stream()
                                        .anyMatch(r -> r.prerequisites().stream().anyMatch(p -> p.featureKey().equals(key))))
                                .map(Map.Entry::getKey).sorted().toList()))
                .toList();
    }

    /**
     * @param featureLevel    the whole feature depends on it
     * @param ruleEnvironments environments with at least one rule depending on it
     */
    public record Dependent(String featureKey, boolean featureLevel, List<String> ruleEnvironments) {
    }

    @Transactional(readOnly = true)
    public Feature get(String key) {
        return persistence.findByKey(key).orElseThrow(() -> new NotFoundException(ENTITY, key));
    }

    @Transactional
    public Feature create(String key, String projectKey, ValueType valueType, JsonNode defaultValue, String description,
                          String owner, List<String> tags) {
        Keys.requireValid(ENTITY, key);
        if (persistence.existsByKey(key)) {
            throw ConflictException.alreadyExists(ENTITY, key);
        }
        if (valueType == null) {
            throw ValidationException.of("valueType is required");
        }
        requireProject(projectKey);
        projectService.requireCanEdit(projectKey);
        requireValue(valueType, defaultValue, "defaultValue");
        ChangeContext context = changeContextProvider.current();
        Instant now = Ids.now(clock);
        Feature feature = new Feature(key, projectKey, valueType, defaultValue, description, owner, tags, false, Map.of(),
                0, now, context.actor(), now, context.actor(), null);
        return commit(context, null, feature, AuditAction.CREATE);
    }

    @Transactional
    public Feature updateMetadata(String key, String projectKey, JsonNode defaultValue, String description, String owner,
                                  List<String> tags, long expectedVersion) {
        Feature current = getForUpdate(key, expectedVersion);
        requireProject(projectKey);
        requireValue(current.valueType(), defaultValue, "defaultValue");
        Feature next = current.withProjectKey(projectKey).withDefaultValue(defaultValue).withDescription(description)
                .withOwner(owner).withTags(tags == null ? List.of() : tags);
        return commit(changeContextProvider.current(), current, next, AuditAction.UPDATE);
    }

    @Transactional
    public Feature updateEnvironment(String key, String environmentKey, boolean enabled, List<Rule> rules,
                                     long expectedVersion) {
        Feature current = getForUpdate(key, expectedVersion);
        environmentService.requireExists(environmentKey);
        List<Rule> validated = ruleValidator.validate(current.valueType(), rules, attributeService.findAllByKey(),
                savedGroupService.findAllByKey().keySet());
        Feature next = current.withEnvironments(withSettings(current, environmentKey, new EnvironmentSettings(enabled, validated)));
        return commit(changeContextProvider.current(), current, next, AuditAction.UPDATE_RULES);
    }

    @Transactional
    public Feature toggle(String key, String environmentKey, boolean enabled, long expectedVersion) {
        Feature current = getForUpdate(key, expectedVersion);
        environmentService.requireExists(environmentKey);
        EnvironmentSettings settings = current.environment(environmentKey).withEnabled(enabled);
        Feature next = current.withEnvironments(withSettings(current, environmentKey, settings));
        return commit(changeContextProvider.current(), current, next, AuditAction.TOGGLE);
    }

    @Transactional
    public Feature setArchived(String key, boolean archived, long expectedVersion) {
        Feature current = getForUpdate(key, expectedVersion);
        return commit(changeContextProvider.current(), current, current.withArchived(archived),
                archived ? AuditAction.ARCHIVE : AuditAction.UNARCHIVE);
    }

    @Transactional(readOnly = true)
    public List<FeatureRevision> revisions(String key) {
        get(key);
        return persistence.findRevisions(key);
    }

    @Transactional(readOnly = true)
    public FeatureRevision revision(String key, int revision) {
        return persistence.findRevision(key, revision)
                .orElseThrow(() -> new NotFoundException(MessageCode.ENTITY_NOT_FOUND,
                        "Revision %d of feature '%s' not found".formatted(revision, key)));
    }

    /**
     * Restores the content of a past revision as a new revision (history is never rewritten). The snapshot is
     * re-validated against today's environments, attributes and saved groups.
     */
    @Transactional
    public Feature restore(String key, int revision, long expectedVersion) {
        Feature current = getForUpdate(key, expectedVersion);
        return apply(current, validate(current.valueType(), revision(key, revision).snapshot()), AuditAction.RESTORE_REVISION);
    }

    /**
     * Validates a whole snapshot against today's catalog (project, environments, attributes, saved groups, value
     * type, prerequisite features) and returns it normalized (missing rule ids assigned). Used for drafts before they
     * can be published.
     */
    @Transactional(readOnly = true)
    public FeatureSnapshot validate(ValueType valueType, FeatureSnapshot snapshot) {
        requireProject(snapshot.projectKey());
        requireValue(valueType, snapshot.defaultValue(), "defaultValue");
        var attributes = attributeService.findAllByKey();
        var groups = savedGroupService.findAllByKey().keySet();
        Map<String, EnvironmentSettings> environments = new HashMap<>();
        snapshot.environments().forEach((env, settings) -> {
            environmentService.requireExists(env);
            environments.put(env, new EnvironmentSettings(settings.enabled(),
                    ruleValidator.validate(valueType, settings.rules(), attributes, groups)));
        });
        FeatureSnapshot validated = new FeatureSnapshot(snapshot.key(), snapshot.projectKey(), valueType, snapshot.defaultValue(),
                snapshot.description(), snapshot.owner(), snapshot.tags(), snapshot.archived(), snapshot.prerequisites(),
                environments);
        Map<String, Feature> features = new HashMap<>();
        persistence.findAllActive().forEach(feature -> features.put(feature.key(), feature));
        prerequisiteValidator.validate(snapshot.key(), validated, features);
        return validated;
    }

    /**
     * Makes a validated snapshot live as one new revision (one audit entry, one publication). The caller is
     * responsible for concurrency checks (drafts check their base revision).
     */
    @Transactional
    public Feature publish(String key, FeatureSnapshot snapshot, AuditAction action) {
        Feature current = get(key);
        return apply(current, validate(current.valueType(), snapshot), action);
    }

    private Feature apply(Feature current, FeatureSnapshot snapshot, AuditAction action) {
        Feature next = current.withProjectKey(snapshot.projectKey()).withDefaultValue(snapshot.defaultValue())
                .withDescription(snapshot.description()).withOwner(snapshot.owner()).withTags(snapshot.tags())
                .withArchived(snapshot.archived()).withPrerequisites(snapshot.prerequisites())
                .withEnvironments(snapshot.environments());
        return commit(changeContextProvider.current(), current, next, action);
    }

    private Feature commit(ChangeContext context, Feature current, Feature next, AuditAction action) {
        Instant now = Ids.now(clock);
        Feature saved = persistence.save(next.withRevision(next.revision() + 1).withUpdatedAt(now).withUpdatedBy(context.actor()));
        persistence.saveRevision(new FeatureRevision(saved.key(), saved.revision(), saved.snapshot(), context.changeId(),
                context.reason(), context.actor(), now));
        auditService.record(context, action, EntityType.FEATURE, saved.key(),
                current == null ? null : current.snapshot(), saved.snapshot());
        events.publishEvent(new ConfigurationChangedEvent(context));
        return saved;
    }

    private Feature getForUpdate(String key, long expectedVersion) {
        Feature current = get(key);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, key, expectedVersion, current.version());
        }
        return current;
    }

    private void requireProject(String projectKey) {
        if (projectKey != null) {
            projectService.get(projectKey);
        }
    }

    private static void requireValue(ValueType type, JsonNode value, String field) {
        if (!type.accepts(value)) {
            throw new ValidationException(MessageCode.INVALID_VALUE, "%s is not a valid %s".formatted(field, type));
        }
    }

    private static Map<String, EnvironmentSettings> withSettings(Feature feature, String environmentKey,
                                                                 EnvironmentSettings settings) {
        Map<String, EnvironmentSettings> environments = new HashMap<>(feature.environments());
        environments.put(environmentKey, settings);
        return environments;
    }
}
