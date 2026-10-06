package com.ktogroup.ktoggle.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.attribute.AttributeService;
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
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class FeatureServiceTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final FeaturePersistencePort persistence = mock(FeaturePersistencePort.class);
    private final RuleValidator ruleValidator = mock(RuleValidator.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);
    private final AttributeService attributeService = mock(AttributeService.class);
    private final SavedGroupService savedGroupService = mock(SavedGroupService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", "because");
    private final FeatureService service = new FeatureService(persistence, ruleValidator,
            new PrerequisiteValidator(new ConditionValidator()), projectService, environmentService,
            attributeService, savedGroupService, auditService, changeContextProvider, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attributeService.findAllByKey()).thenReturn(Map.of());
        when(savedGroupService.findAllByKey()).thenReturn(Map.of());
    }

    @Test
    void creating_a_feature_commits_revision_one_with_audit_and_a_change_event() {
        Feature created = service.create("checkout", "payments", ValueType.BOOLEAN, JSON.booleanNode(false), "d", "me", List.of("t"));

        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.archived()).isFalse();
        assertThat(created.environments()).isEmpty();
        assertThat(created.createdBy()).isEqualTo("alice");
        assertThat(created.updatedBy()).isEqualTo("alice");
        assertThat(created.createdAt()).isEqualTo(NOW);
        verify(projectService).get("payments");
        ArgumentCaptor<FeatureRevision> revision = ArgumentCaptor.forClass(FeatureRevision.class);
        verify(persistence).saveRevision(revision.capture());
        assertThat(revision.getValue().revision()).isEqualTo(1);
        assertThat(revision.getValue().comment()).isEqualTo("because");
        assertThat(revision.getValue().changeId()).isEqualTo(context.changeId());
        assertThat(revision.getValue().snapshot().key()).isEqualTo("checkout");
        verify(auditService).record(eq(context), eq(AuditAction.CREATE), eq(EntityType.FEATURE), eq("checkout"), eq(null), any());
        verify(events).publishEvent(new ConfigurationChangedEvent(context));
    }

    @Test
    void a_feature_without_a_project_does_not_check_projects() {
        service.create("checkout", null, ValueType.STRING, JSON.textNode("a"), null, null, null);

        verify(projectService, never()).get(any());
    }

    @Test
    void creating_a_feature_validates_key_uniqueness_type_project_and_default_value() {
        when(persistence.existsByKey("taken")).thenReturn(true);
        doThrow(new NotFoundException("Project", "ghost")).when(projectService).get("ghost");

        assertThatThrownBy(() -> service.create("bad key", null, ValueType.BOOLEAN, JSON.booleanNode(true), null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create("taken", null, ValueType.BOOLEAN, JSON.booleanNode(true), null, null, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.create("new", null, null, JSON.booleanNode(true), null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("valueType is required");
        assertThatThrownBy(() -> service.create("new", "ghost", ValueType.BOOLEAN, JSON.booleanNode(true), null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create("new", null, ValueType.BOOLEAN, JSON.textNode("true"), null, null, null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.INVALID_VALUE));

        verify(persistence, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void metadata_updates_replace_the_editable_fields_and_bump_the_revision() {
        Feature current = existing(3, 7L);

        Feature updated = service.updateMetadata("checkout", "payments", JSON.booleanNode(true), "new", "owner", null, 7L);

        assertThat(updated.defaultValue().asBoolean()).isTrue();
        assertThat(updated.description()).isEqualTo("new");
        assertThat(updated.tags()).isEmpty();
        assertThat(updated.revision()).isEqualTo(4);
        assertThat(updated.valueType()).isEqualTo(current.valueType());
        verify(auditService).record(eq(context), eq(AuditAction.UPDATE), eq(EntityType.FEATURE), eq("checkout"), any(), any());
    }

    @Test
    void updates_based_on_a_stale_version_are_a_conflict_and_change_nothing() {
        existing(3, 7L);

        assertThatThrownBy(() -> service.updateMetadata("checkout", null, JSON.booleanNode(true), null, null, null, 6L))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.CONCURRENT_MODIFICATION));
        assertThatThrownBy(() -> service.toggle("checkout", "prod", true, 6L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.setArchived("checkout", true, 6L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.updateEnvironment("checkout", "prod", true, List.of(), 6L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.restore("checkout", 1, 6L)).isInstanceOf(ConflictException.class);

        verify(persistence, never()).save(any());
    }

    @Test
    void metadata_updates_reject_values_of_the_wrong_type() {
        existing(3, 7L);

        assertThatThrownBy(() -> service.updateMetadata("checkout", null, JSON.numberNode(1), null, null, null, 7L))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.INVALID_VALUE));
    }

    @Test
    void environment_rules_are_validated_and_stored_with_the_generated_ids() {
        existing(1, 0L);
        Rule generated = new ForceRule("fr_generated", null, true, null, null, JSON.booleanNode(true));
        Rule submitted = new ForceRule(null, null, true, null, null, JSON.booleanNode(true));
        when(ruleValidator.validate(eq(ValueType.BOOLEAN), eq(List.of(submitted)), any(), any())).thenReturn(List.of(generated));

        Feature updated = service.updateEnvironment("checkout", "prod", true, List.of(submitted), 0L);

        verify(environmentService).requireExists("prod");
        assertThat(updated.environment("prod").enabled()).isTrue();
        assertThat(updated.environment("prod").rules()).containsExactly(generated);
        verify(auditService).record(eq(context), eq(AuditAction.UPDATE_RULES), eq(EntityType.FEATURE), eq("checkout"), any(), any());
    }

    @Test
    void invalid_rules_abort_the_update() {
        existing(1, 0L);
        when(ruleValidator.validate(any(), any(), any(), any())).thenThrow(ValidationException.of("bad rules"));

        assertThatThrownBy(() -> service.updateEnvironment("checkout", "prod", true, List.of(), 0L))
                .isInstanceOf(ValidationException.class);

        verify(persistence, never()).save(any());
    }

    @Test
    void toggling_an_environment_without_settings_starts_from_the_disabled_default() {
        existing(1, 0L);

        Feature toggled = service.toggle("checkout", "prod", true, 0L);

        assertThat(toggled.environment("prod").enabled()).isTrue();
        assertThat(toggled.environment("prod").rules()).isEmpty();
        verify(auditService).record(eq(context), eq(AuditAction.TOGGLE), eq(EntityType.FEATURE), eq("checkout"), any(), any());
    }

    @Test
    void toggling_keeps_the_rules_of_the_environment() {
        Rule rule = new ForceRule("fr_a", null, true, null, null, JSON.booleanNode(true));
        when(persistence.findByKey("checkout")).thenReturn(Optional.of(feature(1, 0L, Map.of("prod", new EnvironmentSettings(true, List.of(rule))))));

        Feature toggled = service.toggle("checkout", "prod", false, 0L);

        assertThat(toggled.environment("prod").enabled()).isFalse();
        assertThat(toggled.environment("prod").rules()).containsExactly(rule);
    }

    @Test
    void archiving_and_unarchiving_are_audited_with_their_own_actions() {
        existing(1, 0L);

        assertThat(service.setArchived("checkout", true, 0L).archived()).isTrue();
        verify(auditService).record(eq(context), eq(AuditAction.ARCHIVE), any(), any(), any(), any());

        when(persistence.findByKey("checkout")).thenReturn(Optional.of(feature(2, 1L, Map.of()).withArchived(true)));
        assertThat(service.setArchived("checkout", false, 1L).archived()).isFalse();
        verify(auditService).record(eq(context), eq(AuditAction.UNARCHIVE), any(), any(), any(), any());
    }

    @Test
    void a_parent_with_active_dependents_cannot_be_archived_or_moved_away_from_them() {
        existing(1, 0L);
        Prerequisite onCheckout = new Prerequisite("checkout", JSON.objectNode().set("value", JSON.objectNode().put("$exists", true)));
        Rule gated = new ForceRule("fr_g", null, true, null, null, JSON.booleanNode(true), null, List.of(onCheckout), null, null);
        Feature featureLevel = feature(1, 0L, Map.of()).withKey("upsell").withPrerequisites(List.of(onCheckout));
        Feature ruleLevel = feature(1, 0L, Map.of("prod", new EnvironmentSettings(true, List.of(gated)))).withKey("banner");
        when(persistence.findAllActive()).thenReturn(List.of(featureLevel, ruleLevel));

        assertThatThrownBy(() -> service.setArchived("checkout", true, 0L)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("banner, upsell").hasMessageContaining("archiving");
        assertThatThrownBy(() -> service.updateMetadata("checkout", "payments", JSON.booleanNode(false), null, null, null, 0L))
                .isInstanceOf(ValidationException.class).hasMessageContaining("another project");
        verify(persistence, never()).save(any());

        when(persistence.findAllActive()).thenReturn(List.of(feature(1, 0L, Map.of()).withKey("unrelated")));
        assertThat(service.setArchived("checkout", true, 0L).archived()).isTrue();
    }

    @Test
    void unknown_features_and_revisions_are_not_found() {
        when(persistence.findByKey("ghost")).thenReturn(Optional.empty());
        existing(1, 0L);
        when(persistence.findRevision("checkout", 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.revisions("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.revision("checkout", 9)).isInstanceOf(NotFoundException.class).hasMessageContaining("Revision 9");
        assertThatThrownBy(() -> service.restore("checkout", 9, 0L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoring_a_revision_applies_its_snapshot_as_a_new_revision() {
        Rule rule = new ForceRule("fr_a", null, true, null, null, JSON.booleanNode(true));
        Feature current = feature(5, 4L, Map.of("prod", EnvironmentSettings.DISABLED)).withDescription("current");
        when(persistence.findByKey("checkout")).thenReturn(Optional.of(current));
        FeatureSnapshot snapshot = new FeatureSnapshot("checkout", "payments", ValueType.BOOLEAN, JSON.booleanNode(true), "old", "o",
                List.of("t"), false, Map.of("prod", new EnvironmentSettings(true, List.of(rule))));
        when(persistence.findRevision("checkout", 2)).thenReturn(Optional.of(new FeatureRevision("checkout", 2, snapshot, Ids.newId(),
                null, "bob", NOW)));
        when(ruleValidator.validate(any(), any(), any(), any())).thenReturn(List.of(rule));

        Feature restored = service.restore("checkout", 2, 4L);

        assertThat(restored.description()).isEqualTo("old");
        assertThat(restored.projectKey()).isEqualTo("payments");
        assertThat(restored.tags()).containsExactly("t");
        assertThat(restored.environment("prod").enabled()).isTrue();
        assertThat(restored.revision()).isEqualTo(6);
        verify(projectService).get("payments");
        verify(environmentService).requireExists("prod");
        verify(auditService).record(eq(context), eq(AuditAction.RESTORE_REVISION), eq(EntityType.FEATURE), eq("checkout"), any(), any());
    }

    @Test
    void restoring_a_revision_that_references_a_deleted_environment_fails() {
        existing(5, 4L);
        FeatureSnapshot snapshot = new FeatureSnapshot("checkout", null, ValueType.BOOLEAN, JSON.booleanNode(true), null, null, List.of(),
                false, Map.of("gone", EnvironmentSettings.DISABLED));
        when(persistence.findRevision("checkout", 2)).thenReturn(Optional.of(new FeatureRevision("checkout", 2, snapshot, Ids.newId(),
                null, "bob", NOW)));
        doThrow(new NotFoundException("Environment", "gone")).when(environmentService).requireExists("gone");

        assertThatThrownBy(() -> service.restore("checkout", 2, 4L)).isInstanceOf(NotFoundException.class);

        verify(persistence, never()).save(any());
    }

    @Test
    void listing_passes_the_filter_through() {
        FeaturePersistencePort.FeatureFilter filter = new FeaturePersistencePort.FeatureFilter("p", "t", "s", false);
        when(persistence.findAll(filter)).thenReturn(List.of());

        assertThat(service.findAll(filter)).isEmpty();
        verify(persistence).findAll(filter);
    }

    private Feature existing(int revision, long version) {
        Feature feature = feature(revision, version, Map.of());
        when(persistence.findByKey("checkout")).thenReturn(Optional.of(feature));
        return feature;
    }

    private static Feature feature(int revision, long version, Map<String, EnvironmentSettings> environments) {
        JsonNode defaultValue = JSON.booleanNode(false);
        return new Feature("checkout", null, ValueType.BOOLEAN, defaultValue, "d", "o", List.of("a"), false, environments, revision, NOW,
                "bob", NOW, "bob", version);
    }
}
