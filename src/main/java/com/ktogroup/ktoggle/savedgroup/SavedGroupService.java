package com.ktogroup.ktoggle.savedgroup;

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
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SavedGroupService {

    private static final String ENTITY = "Saved group";
    /** Saved groups are inlined into every payload that uses them, so huge lists bloat every SDK download. */
    static final int MAX_LIST_SIZE = 10_000;

    private final SavedGroupPersistencePort persistence;
    private final AttributeService attributeService;
    private final ConditionValidator conditionValidator;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SavedGroup> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, SavedGroup> findAllByKey() {
        return persistence.findAll().stream().collect(Collectors.toMap(SavedGroup::key, Function.identity()));
    }

    @Transactional(readOnly = true)
    public SavedGroup get(String key) {
        return persistence.findByKey(key).orElseThrow(() -> new NotFoundException(ENTITY, key));
    }

    @Transactional
    public SavedGroup create(SavedGroupCommand command) {
        Keys.requireValid(ENTITY, command.key());
        if (persistence.existsByKey(command.key())) {
            throw ConflictException.alreadyExists(ENTITY, command.key());
        }
        validate(command);
        Instant now = Ids.now(clock);
        SavedGroup saved = persistence.save(toDomain(command, now, now, null));
        auditService.record(changeContextProvider.current(), AuditAction.CREATE, EntityType.SAVED_GROUP, saved.key(), null, saved);
        return saved;
    }

    @Transactional
    public SavedGroup update(String key, SavedGroupCommand command, long expectedVersion) {
        SavedGroup current = get(key);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, key, expectedVersion, current.version());
        }
        if (command.type() != current.type()) {
            throw ValidationException.of("The type of a saved group cannot change");
        }
        validate(command);
        SavedGroup saved = persistence.save(toDomain(command, current.createdAt(), Ids.now(clock), current.version()));
        ChangeContext context = changeContextProvider.current();
        auditService.record(context, AuditAction.UPDATE, EntityType.SAVED_GROUP, key, current, saved);
        events.publishEvent(new ConfigurationChangedEvent(context));
        return saved;
    }

    @Transactional
    public void delete(String key) {
        SavedGroup current = get(key);
        if (persistence.isReferenced(key)) {
            throw ConflictException.inUse(ENTITY, key, "feature rules");
        }
        persistence.delete(key);
        auditService.record(changeContextProvider.current(), AuditAction.DELETE, EntityType.SAVED_GROUP, key, current, null);
    }

    private void validate(SavedGroupCommand command) {
        if (command.type() == null) {
            throw ValidationException.of("type is required");
        }
        switch (command.type()) {
            case LIST -> {
                if (command.attributeKey() == null || command.values() == null) {
                    throw ValidationException.of("LIST saved groups require attributeKey and values");
                }
                attributeService.get(command.attributeKey());
                if (command.values().size() > MAX_LIST_SIZE) {
                    throw ValidationException.of("LIST saved groups are limited to %d values".formatted(MAX_LIST_SIZE));
                }
                boolean allScalars = command.values().stream().allMatch(v -> v.isTextual() || v.isNumber());
                if (!allScalars) {
                    throw ValidationException.of("LIST values must be strings or numbers");
                }
            }
            case CONDITION -> {
                if (command.condition() == null || !command.condition().isObject() || command.condition().isEmpty()) {
                    throw ValidationException.of("CONDITION saved groups require a non-empty condition");
                }
                conditionValidator.validate(command.condition(), attributeService.findAllByKey().keySet());
            }
        }
    }

    private static SavedGroup toDomain(SavedGroupCommand c, Instant createdAt, Instant updatedAt, Long version) {
        boolean list = c.type() == SavedGroupType.LIST;
        return new SavedGroup(c.key(), c.name(), c.description(), c.type(), list ? c.attributeKey() : null,
                list ? List.copyOf(c.values()) : null, list ? null : c.condition(), createdAt, updatedAt, version);
    }

    public record SavedGroupCommand(String key, String name, String description, SavedGroupType type, String attributeKey,
                                    List<JsonNode> values, JsonNode condition) {
    }
}
