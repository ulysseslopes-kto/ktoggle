package com.ktogroup.ktoggle.environment;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.Keys;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private static final String ENTITY = "Environment";

    private final EnvironmentPersistencePort persistence;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Environment> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public Environment get(String key) {
        return persistence.findByKey(key).orElseThrow(() -> new NotFoundException(ENTITY, key));
    }

    @Transactional(readOnly = true)
    public void requireExists(String key) {
        if (!persistence.existsByKey(key)) {
            throw new NotFoundException(ENTITY, key);
        }
    }

    @Transactional
    public Environment create(String key, String name, String description, int sortOrder) {
        return create(key, name, description, sortOrder, false);
    }

    @Transactional
    public Environment create(String key, String name, String description, int sortOrder, boolean requiresReview) {
        Keys.requireValid(ENTITY, key);
        if (persistence.existsByKey(key)) {
            throw ConflictException.alreadyExists(ENTITY, key);
        }
        Instant now = Ids.now(clock);
        Environment saved = persistence.save(new Environment(key, name, description, sortOrder, requiresReview, now, now, null));
        auditService.record(changeContextProvider.current(), AuditAction.CREATE, EntityType.ENVIRONMENT, key, null, saved);
        return saved;
    }

    @Transactional
    public Environment update(String key, String name, String description, int sortOrder, boolean requiresReview,
                              long expectedVersion) {
        Environment current = get(key);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, key, expectedVersion, current.version());
        }
        Environment saved = persistence.save(new Environment(key, name, description, sortOrder, requiresReview, current.createdAt(),
                Ids.now(clock), current.version()));
        auditService.record(changeContextProvider.current(), AuditAction.UPDATE, EntityType.ENVIRONMENT, key, current, saved);
        return saved;
    }

    @Transactional
    public void delete(String key) {
        Environment current = get(key);
        if (persistence.isInUse(key)) {
            throw ConflictException.inUse(ENTITY, key, "an SDK connection or an enabled feature");
        }
        persistence.delete(key);
        auditService.record(changeContextProvider.current(), AuditAction.DELETE, EntityType.ENVIRONMENT, key, current, null);
    }
}
