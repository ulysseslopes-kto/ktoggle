package com.ktogroup.ktoggle.savedgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.targeting.ConditionValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SavedGroupServiceTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final SavedGroupPersistencePort persistence = mock(SavedGroupPersistencePort.class);
    private final AttributeService attributeService = mock(AttributeService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
    private final SavedGroupService service = new SavedGroupService(persistence, attributeService, new ConditionValidator(), auditService,
            changeContextProvider, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final SavedGroup existing = new SavedGroup("latam", "Latam", null, SavedGroupType.LIST, "country",
            List.of(JSON.textNode("BR")), null, NOW.minusSeconds(60), NOW.minusSeconds(60), 3L);

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findByKey("latam")).thenReturn(Optional.of(existing));
        when(attributeService.findAllByKey()).thenReturn(Map.of("country", mock(com.ktogroup.ktoggle.attribute.Attribute.class)));
    }

    @Test
    void list_groups_are_validated_against_a_declared_attribute_and_audited() {
        SavedGroup created = service.create(list("vips", "country", List.of(JSON.textNode("BR"), JSON.numberNode(7))));

        assertThat(created.values()).hasSize(2);
        assertThat(created.condition()).isNull();
        verify(attributeService).get("country");
        verify(auditService).record(context, AuditAction.CREATE, EntityType.SAVED_GROUP, "vips", null, created);
    }

    @Test
    void list_groups_need_an_attribute_and_values_of_scalar_types_within_the_size_limit() {
        assertThatThrownBy(() -> service.create(list("g", null, List.of(JSON.textNode("a"))))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(list("g", "country", null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(list("g", "country", List.of(JSON.booleanNode(true)))))
                .hasMessageContaining("strings or numbers");
        assertThatThrownBy(() -> service.create(list("g", "country", List.of(JSON.arrayNode())))).isInstanceOf(ValidationException.class);

        List<JsonNode> tooMany = new ArrayList<>();
        for (int i = 0; i <= SavedGroupService.MAX_LIST_SIZE; i++) {
            tooMany.add(JSON.numberNode(i));
        }
        assertThatThrownBy(() -> service.create(list("g", "country", tooMany))).hasMessageContaining("limited to");
        assertThat(service.create(list("g", "country", tooMany.subList(0, SavedGroupService.MAX_LIST_SIZE))).values())
                .hasSize(SavedGroupService.MAX_LIST_SIZE);
    }

    @Test
    void list_groups_over_an_unknown_attribute_are_not_found() {
        when(attributeService.get("ghost")).thenThrow(new NotFoundException("Attribute", "ghost"));

        assertThatThrownBy(() -> service.create(list("g", "ghost", List.of(JSON.textNode("a"))))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void condition_groups_need_a_non_empty_object_over_declared_attributes() {
        SavedGroup created = service.create(condition("gold", JSON.objectNode().put("country", "BR")));

        assertThat(created.condition().path("country").asText()).isEqualTo("BR");
        assertThat(created.attributeKey()).isNull();
        assertThat(created.values()).isNull();
        assertThatThrownBy(() -> service.create(condition("g", null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(condition("g", JSON.objectNode()))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(condition("g", JSON.arrayNode().add("x")))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(condition("g", JSON.objectNode().put("ghost", "x"))))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.INVALID_CONDITION));
    }

    @Test
    void a_type_is_required() {
        assertThatThrownBy(() -> service.create(new SavedGroupCommand("g", "G", null, null, null, null, null)))
                .hasMessageContaining("type is required");
    }

    @Test
    void creating_rejects_invalid_and_duplicated_keys() {
        when(persistence.existsByKey("latam")).thenReturn(true);

        assertThatThrownBy(() -> service.create(list("bad key", "country", List.of(JSON.textNode("a"))))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(list("latam", "country", List.of(JSON.textNode("a"))))).isInstanceOf(ConflictException.class);
    }

    @Test
    void updating_republishes_because_bundles_inline_group_contents() {
        SavedGroup updated = service.update("latam", list("latam", "country", List.of(JSON.textNode("AR"))), 3L);

        assertThat(updated.values()).containsExactly(JSON.textNode("AR"));
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        verify(auditService).record(context, AuditAction.UPDATE, EntityType.SAVED_GROUP, "latam", existing, updated);
        verify(events).publishEvent(new ConfigurationChangedEvent(context));
    }

    @Test
    void updating_cannot_change_the_type_or_use_a_stale_version() {
        assertThatThrownBy(() -> service.update("latam", condition("latam", JSON.objectNode().put("country", "BR")), 3L))
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> service.update("latam", list("latam", "country", List.of(JSON.textNode("a"))), 2L))
                .isInstanceOf(ConflictException.class);

        verify(persistence, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void a_group_used_by_rules_cannot_be_deleted() {
        when(persistence.isReferenced("latam")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("latam")).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.ENTITY_IN_USE));

        verify(persistence, never()).delete(any());
    }

    @Test
    void deleting_an_unused_group_is_audited() {
        service.delete("latam");

        verify(persistence).delete("latam");
        verify(auditService).record(context, AuditAction.DELETE, EntityType.SAVED_GROUP, "latam", existing, null);
    }

    @Test
    void lookups_report_unknown_groups_and_index_by_key() {
        when(persistence.findByKey("ghost")).thenReturn(Optional.empty());
        when(persistence.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.get("ghost")).isInstanceOf(NotFoundException.class);
        assertThat(service.findAll()).containsExactly(existing);
        assertThat(service.findAllByKey()).containsEntry("latam", existing);
    }

    @Test
    void a_list_group_stands_for_an_in_condition_and_a_condition_group_for_itself() {
        assertThat(existing.toCondition().toString()).isEqualTo("{\"country\":{\"$in\":[\"BR\"]}}");
        JsonNode condition = JSON.objectNode().put("country", "AR");
        SavedGroup group = service.create(condition("gold", condition));

        assertThat(group.toCondition()).isEqualTo(condition);
    }

    private static SavedGroupCommand list(String key, String attribute, List<JsonNode> values) {
        return new SavedGroupCommand(key, key, null, SavedGroupType.LIST, attribute, values, null);
    }

    private static SavedGroupCommand condition(String key, JsonNode condition) {
        return new SavedGroupCommand(key, key, null, SavedGroupType.CONDITION, null, null, condition);
    }
}
