package com.ktogroup.ktoggle.attribute;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.Keys;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Attributes are never hard-deleted (conditions and history refer to them by key): they are archived. */
@Service
@RequiredArgsConstructor
public class AttributeService {

    private static final String ENTITY = "Attribute";

    private final AttributePersistencePort persistence;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Attribute> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, Attribute> findAllByKey() {
        return persistence.findAll().stream().collect(Collectors.toMap(Attribute::key, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Attribute get(String key) {
        return persistence.findByKey(key).orElseThrow(() -> new NotFoundException(ENTITY, key));
    }

    @Transactional
    public Attribute create(AttributeCommand command) {
        Keys.requireValid(ENTITY, command.key());
        if (persistence.existsByKey(command.key())) {
            throw ConflictException.alreadyExists(ENTITY, command.key());
        }
        validate(command);
        Instant now = Ids.now(clock);
        Attribute saved = persistence.save(new Attribute(command.key(), command.datatype(), command.description(),
                command.hashAttribute(), command.pii(), enumValues(command), command.archived(), now, now, null));
        auditService.record(changeContextProvider.current(), AuditAction.CREATE, EntityType.ATTRIBUTE, saved.key(), null, saved);
        return saved;
    }

    @Transactional
    public Attribute update(String key, AttributeCommand command, long expectedVersion) {
        Attribute current = get(key);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, key, expectedVersion, current.version());
        }
        validate(command);
        Attribute saved = persistence.save(new Attribute(key, command.datatype(), command.description(),
                command.hashAttribute(), command.pii(), enumValues(command), command.archived(), current.createdAt(),
                Ids.now(clock), current.version()));
        auditService.record(changeContextProvider.current(), AuditAction.UPDATE, EntityType.ATTRIBUTE, key, current, saved);
        return saved;
    }

    private static void validate(AttributeCommand command) {
        if (command.datatype() == AttributeDatatype.ENUM && (command.enumValues() == null || command.enumValues().isEmpty())) {
            throw ValidationException.of("ENUM attributes require enumValues");
        }
        if (command.hashAttribute() && command.datatype() != AttributeDatatype.STRING
                && command.datatype() != AttributeDatatype.NUMBER) {
            throw ValidationException.of("Only STRING or NUMBER attributes can be used as hash attributes");
        }
    }

    private static List<String> enumValues(AttributeCommand command) {
        return command.datatype() == AttributeDatatype.ENUM ? List.copyOf(command.enumValues()) : List.of();
    }

    public record AttributeCommand(String key, AttributeDatatype datatype, String description, boolean hashAttribute,
                                   boolean pii, List<String> enumValues, boolean archived) {
    }
}
