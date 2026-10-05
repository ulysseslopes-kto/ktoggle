package com.ktogroup.ktoggle.project;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.Keys;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.ForbiddenException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.security.CurrentUser;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final String ENTITY = "Project";
    /** Same value as SecurityConfiguration.ADMIN (not imported: config depends on feature packages, not the reverse). */
    private static final String ADMIN_ROLE = "ktoggle-admin";

    private final ProjectPersistencePort persistence;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final ApplicationEventPublisher events;
    private final CurrentUser currentUser;
    private final Clock clock;

    /** Trimmed, de-duplicated, without blanks. */
    private static List<String> clean(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).distinct().toList();
    }

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public Project get(String key) {
        return persistence.findByKey(key).orElseThrow(() -> new NotFoundException(ENTITY, key));
    }

    /**
     * Fails unless the caller may change features of {@code projectKey}: features without a project and unrestricted
     * projects are open to every editor; restricted ones to admins and the listed roles and users.
     */
    @Transactional(readOnly = true)
    public void requireCanEdit(String projectKey) {
        if (!canEdit(projectKey)) {
            throw new ForbiddenException(MessageCode.NOT_ALLOWED,
                    "Only the editors of project '%s' can change its features".formatted(projectKey));
        }
    }

    @Transactional(readOnly = true)
    public boolean canEdit(String projectKey) {
        if (projectKey == null || currentUser.roles().contains(ADMIN_ROLE)) {
            return true;
        }
        return persistence.findByKey(projectKey).map(p -> p.editableBy(currentUser.username(), currentUser.roles())).orElse(true);
    }

    @Transactional
    public Project create(String key, String name, String description) {
        return create(key, name, description, List.of(), List.of());
    }

    @Transactional
    public Project create(String key, String name, String description, List<String> editorRoles, List<String> editorUsers) {
        Keys.requireValid(ENTITY, key);
        if (persistence.existsByKey(key)) {
            throw ConflictException.alreadyExists(ENTITY, key);
        }
        Instant now = Ids.now(clock);
        Project saved = persistence.save(new Project(key, name, description, clean(editorRoles), clean(editorUsers), now, now,
                null));
        auditService.record(changeContextProvider.current(), AuditAction.CREATE, EntityType.PROJECT, key, null, saved);
        return saved;
    }

    @Transactional
    public Project update(String key, String name, String description, long expectedVersion) {
        Project current = get(key);
        return update(key, name, description, current.editorRoles(), current.editorUsers(), expectedVersion);
    }

    @Transactional
    public Project update(String key, String name, String description, List<String> editorRoles, List<String> editorUsers,
                          long expectedVersion) {
        Project current = get(key);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, key, expectedVersion, current.version());
        }
        Project saved = persistence.save(new Project(key, name, description, clean(editorRoles), clean(editorUsers),
                current.createdAt(), Ids.now(clock), current.version()));
        auditService.record(changeContextProvider.current(), AuditAction.UPDATE, EntityType.PROJECT, key, current, saved);
        return saved;
    }

    @Transactional
    public void delete(String key) {
        Project current = get(key);
        if (persistence.isReferenced(key)) {
            throw ConflictException.inUse(ENTITY, key, "features or SDK connections");
        }
        persistence.delete(key);
        ChangeContext context = changeContextProvider.current();
        auditService.record(context, AuditAction.DELETE, EntityType.PROJECT, key, current, null);
        events.publishEvent(new ConfigurationChangedEvent(context));
    }
}
