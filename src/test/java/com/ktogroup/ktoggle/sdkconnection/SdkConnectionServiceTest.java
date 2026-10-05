package com.ktogroup.ktoggle.sdkconnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.project.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SdkConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final SdkConnectionPersistencePort persistence = mock(SdkConnectionPersistencePort.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
    private final SdkConnectionService service = new SdkConnectionService(persistence, environmentService, projectService, auditService,
            changeContextProvider, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final SdkConnection existing = new SdkConnection("sdk-abcdefgh12", "web", "prod", List.of("a"), "pin".repeat(21) + "x",
            NOW.minusSeconds(60), NOW.minusSeconds(60), 5L);

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findByClientKey("sdk-abcdefgh12")).thenReturn(Optional.of(existing));
    }

    @Test
    void a_client_key_is_generated_when_none_is_given() {
        SdkConnection created = service.create(null, "web", "prod", null);

        assertThat(created.clientKey()).matches("^sdk-[A-Za-z0-9]{16}$");
        assertThat(created.projectKeys()).isEmpty();
        assertThat(created.pinnedBundleHash()).isNull();
        verify(environmentService).requireExists("prod");
        verify(auditService).record(context, AuditAction.CREATE, EntityType.SDK_CONNECTION, created.clientKey(), null, created);
        verify(events).publishEvent(new ConfigurationChangedEvent(context));
    }

    @Test
    void generated_client_keys_are_unique() {
        assertThat(service.create(null, "a", "prod", null).clientKey()).isNotEqualTo(service.create(null, "b", "prod", null).clientKey());
    }

    @Test
    void an_imported_client_key_is_kept_after_validation() {
        assertThat(service.create("sdk-ImportedKey123", "web", "prod", List.of()).clientKey()).isEqualTo("sdk-ImportedKey123");
        for (String invalid : List.of("nope", "sdk-short", "sdk-" + "a".repeat(65), "sdk-bad_chars!!", "SDK-abcdefgh12")) {
            assertThatThrownBy(() -> service.create(invalid, "web", "prod", null)).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void duplicated_client_keys_and_unknown_environments_or_projects_are_rejected() {
        doThrow(new NotFoundException("Environment", "ghost")).when(environmentService).requireExists("ghost");
        doThrow(new NotFoundException("Project", "ghost")).when(projectService).get("ghost");

        when(persistence.existsByClientKey("sdk-abcdefgh12")).thenReturn(true);
        assertThatThrownBy(() -> service.create("sdk-abcdefgh12", "x", "prod", null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.create(null, "x", "ghost", null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(null, "x", "prod", List.of("ghost"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void project_keys_are_deduplicated_and_sorted() {
        assertThat(service.create(null, "x", "prod", List.of("b", "a", "b")).projectKeys()).containsExactly("a", "b");
    }

    @Test
    void updating_keeps_the_environment_and_the_pin_and_republishes() {
        SdkConnection updated = service.update("sdk-abcdefgh12", "web 2", List.of("b"), 5L);

        assertThat(updated.environmentKey()).isEqualTo("prod");
        assertThat(updated.pinnedBundleHash()).isEqualTo(existing.pinnedBundleHash());
        assertThat(updated.projectKeys()).containsExactly("b");
        verify(auditService).record(context, AuditAction.UPDATE, EntityType.SDK_CONNECTION, "sdk-abcdefgh12", existing, updated);
        verify(events).publishEvent(new ConfigurationChangedEvent(context));
    }

    @Test
    void updating_with_a_stale_version_or_unknown_key_fails() {
        assertThatThrownBy(() -> service.update("sdk-abcdefgh12", "x", null, 4L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update("sdk-missing000", "x", null, 0L)).isInstanceOf(NotFoundException.class);

        verify(persistence, never()).save(any());
    }

    @Test
    void pinning_and_unpinning_change_only_the_pin() {
        SdkConnection pinned = service.setPinnedBundle("sdk-abcdefgh12", "f".repeat(64));
        SdkConnection unpinned = service.setPinnedBundle("sdk-abcdefgh12", null);

        assertThat(pinned.pinnedBundleHash()).isEqualTo("f".repeat(64));
        assertThat(unpinned.pinnedBundleHash()).isNull();
        assertThat(unpinned.projectKeys()).isEqualTo(existing.projectKeys());
        assertThat(unpinned.version()).isEqualTo(existing.version());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void lookups_report_unknown_connections() {
        when(persistence.findByClientKey("sdk-missing000")).thenReturn(Optional.empty());
        when(persistence.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.get("sdk-missing000")).isInstanceOf(NotFoundException.class);
        assertThat(service.findAll()).containsExactly(existing);
    }

    @Test
    void a_connection_without_projects_includes_every_project_otherwise_only_its_own() {
        SdkConnection all = new SdkConnection("sdk-x", "n", "prod", List.of(), null, NOW, NOW, 0L);

        assertThat(all.includesProject("a")).isTrue();
        assertThat(all.includesProject(null)).isTrue();
        assertThat(existing.includesProject("a")).isTrue();
        assertThat(existing.includesProject("b")).isFalse();
        assertThat(existing.includesProject(null)).isFalse();
    }
}
