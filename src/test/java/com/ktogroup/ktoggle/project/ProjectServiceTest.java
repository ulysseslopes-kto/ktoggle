package com.ktogroup.ktoggle.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.security.CurrentUser;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final ProjectPersistencePort persistence = mock(ProjectPersistencePort.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ProjectService service = new ProjectService(persistence, auditService, changeContextProvider, events,
            currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Project existing = new Project("payments", "Payments", "d", NOW.minusSeconds(60), NOW.minusSeconds(60), 2L);

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findByKey("payments")).thenReturn(Optional.of(existing));
    }

    @Test
    void restricted_projects_are_editable_only_by_their_roles_users_and_admins() {
        Project restricted = new Project("casino", "Casino", null, List.of("squad-casino"), List.of("bob", "token:ci-casino"),
                NOW, NOW, 0L);
        when(persistence.findByKey("casino")).thenReturn(Optional.of(restricted));

        as("eve", "ktoggle-editor");
        assertThat(service.canEdit("casino")).isFalse();
        assertThatThrownBy(() -> service.requireCanEdit("casino")).hasMessageContaining("Only the editors of project 'casino'");
        assertThat(service.canEdit("payments")).as("unrestricted project").isTrue();
        assertThat(service.canEdit(null)).as("features without a project").isTrue();

        as("bob", "ktoggle-editor");
        assertThat(service.canEdit("casino")).isTrue();
        as("token:ci-casino", "ktoggle-editor");
        assertThat(service.canEdit("casino")).as("API tokens are listed by name").isTrue();
        as("carol", "ktoggle-editor", "squad-casino");
        assertThat(service.canEdit("casino")).isTrue();
        as("root", "ktoggle-admin");
        assertThat(service.canEdit("casino")).isTrue();
    }

    @Test
    void editor_lists_are_cleaned_and_kept_on_plain_updates() {
        Project created = service.create("growth", "Growth", "d", List.of(" squad-growth ", "", "squad-growth"), null);
        assertThat(created.editorRoles()).containsExactly("squad-growth");
        assertThat(created.restricted()).isTrue();

        Project withEditors = new Project("payments", "Payments", "d", List.of("squad-payments"), List.of(), NOW, NOW, 2L);
        when(persistence.findByKey("payments")).thenReturn(Optional.of(withEditors));
        assertThat(service.update("payments", "Payments 2", "d", 2L).editorRoles()).containsExactly("squad-payments");
    }

    private void as(String user, String... roles) {
        when(currentUser.username()).thenReturn(user);
        when(currentUser.roles()).thenReturn(Set.of(roles));
    }

    @Test
    void creating_a_project_stamps_it_and_audits_the_creation() {
        Project created = service.create("growth", "Growth", "d");

        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(created.version()).isNull();
        verify(auditService).record(context, AuditAction.CREATE, EntityType.PROJECT, "growth", null, created);
    }

    @Test
    void creating_rejects_invalid_and_duplicated_keys() {
        when(persistence.existsByKey("payments")).thenReturn(true);

        assertThatThrownBy(() -> service.create("not valid!", "x", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(null, "x", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create("payments", "x", null)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(persistence, never()).save(any());
    }

    @Test
    void updating_keeps_the_creation_time_and_audits_before_and_after() {
        Project updated = service.update("payments", "Payments 2", "d2", 2L);

        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        assertThat(updated.name()).isEqualTo("Payments 2");
        verify(auditService).record(context, AuditAction.UPDATE, EntityType.PROJECT, "payments", existing, updated);
    }

    @Test
    void updating_with_a_stale_version_is_a_conflict() {
        assertThatThrownBy(() -> service.update("payments", "x", null, 1L))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.CONCURRENT_MODIFICATION));

        verify(persistence, never()).save(any());
    }

    @Test
    void unknown_projects_are_not_found() {
        when(persistence.findByKey("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update("ghost", "x", null, 0L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete("ghost")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void a_referenced_project_cannot_be_deleted() {
        when(persistence.isReferenced("payments")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("payments")).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.ENTITY_IN_USE));

        verify(persistence, never()).delete(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void deleting_an_unused_project_is_audited_and_triggers_a_republication() {
        service.delete("payments");

        verify(persistence).delete("payments");
        verify(auditService).record(context, AuditAction.DELETE, EntityType.PROJECT, "payments", existing, null);
        verify(events).publishEvent(new ConfigurationChangedEvent(context));
    }

    @Test
    void listing_returns_what_the_store_has() {
        when(persistence.findAll()).thenReturn(List.of(existing));

        assertThat(service.findAll()).containsExactly(existing);
        assertThat(service.get("payments")).isEqualTo(existing);
        verify(persistence, never()).save(eq(existing));
    }
}
