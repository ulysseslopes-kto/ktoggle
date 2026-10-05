package com.ktogroup.ktoggle.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnvironmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final EnvironmentPersistencePort persistence = mock(EnvironmentPersistencePort.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
    private final EnvironmentService service = new EnvironmentService(persistence, auditService, changeContextProvider,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Environment existing = new Environment("prod", "Production", "d", 1, false, NOW.minusSeconds(60), NOW.minusSeconds(60), 4L);

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findByKey("prod")).thenReturn(Optional.of(existing));
    }

    @Test
    void creating_an_environment_audits_it() {
        Environment created = service.create("staging", "Staging", null, 2);

        assertThat(created.sortOrder()).isEqualTo(2);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.requiresReview()).isFalse();
        verify(auditService).record(context, AuditAction.CREATE, EntityType.ENVIRONMENT, "staging", null, created);
    }

    @Test
    void creating_with_review_sets_the_flag() {
        assertThat(service.create("prd", "Production", null, 3, true).requiresReview()).isTrue();
    }

    @Test
    void creating_rejects_invalid_and_duplicated_keys() {
        when(persistence.existsByKey("prod")).thenReturn(true);

        assertThatThrownBy(() -> service.create("no spaces", "x", null, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create("prod", "x", null, 0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void updating_requires_the_current_version() {
        Environment updated = service.update("prod", "Prod 2", "d", 9, true, 4L);

        assertThat(updated.sortOrder()).isEqualTo(9);
        assertThat(updated.requiresReview()).isTrue();
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        verify(auditService).record(context, AuditAction.UPDATE, EntityType.ENVIRONMENT, "prod", existing, updated);
        assertThatThrownBy(() -> service.update("prod", "x", null, 0, false, 3L)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void an_environment_in_use_cannot_be_deleted() {
        when(persistence.isInUse("prod")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("prod")).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.ENTITY_IN_USE));

        verify(persistence, never()).delete(any());
    }

    @Test
    void deleting_an_unused_environment_is_audited() {
        service.delete("prod");

        verify(persistence).delete("prod");
        verify(auditService).record(context, AuditAction.DELETE, EntityType.ENVIRONMENT, "prod", existing, null);
    }

    @Test
    void existence_checks_and_lookups_report_unknown_environments() {
        when(persistence.existsByKey("prod")).thenReturn(true);
        when(persistence.findByKey("ghost")).thenReturn(Optional.empty());
        when(persistence.findAll()).thenReturn(List.of(existing));

        service.requireExists("prod");
        assertThatThrownBy(() -> service.requireExists("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete("ghost")).isInstanceOf(NotFoundException.class);
        assertThat(service.findAll()).containsExactly(existing);
    }
}
