package com.ktogroup.ktoggle.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.attribute.AttributeService.AttributeCommand;
import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttributeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final AttributePersistencePort persistence = mock(AttributePersistencePort.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ChangeContextProvider changeContextProvider = mock(ChangeContextProvider.class);
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", null);
    private final AttributeService service = new AttributeService(persistence, auditService, changeContextProvider,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Attribute existing = new Attribute("country", AttributeDatatype.STRING, "d", false, false, List.of(), false,
            NOW.minusSeconds(60), NOW.minusSeconds(60), 1L);

    @BeforeEach
    void setUp() {
        when(changeContextProvider.current()).thenReturn(context);
        when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findByKey("country")).thenReturn(Optional.of(existing));
    }

    @Test
    void creating_an_attribute_audits_it() {
        Attribute created = service.create(command("plan", AttributeDatatype.STRING, false, null));

        assertThat(created.key()).isEqualTo("plan");
        assertThat(created.createdAt()).isEqualTo(NOW);
        verify(auditService).record(context, AuditAction.CREATE, EntityType.ATTRIBUTE, "plan", null, created);
    }

    @Test
    void enum_attributes_need_values_and_keep_them_while_other_types_drop_them() {
        assertThatThrownBy(() -> service.create(command("plan", AttributeDatatype.ENUM, false, null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(command("plan", AttributeDatatype.ENUM, false, List.of()))).isInstanceOf(ValidationException.class);

        assertThat(service.create(command("plan", AttributeDatatype.ENUM, false, new ArrayList<>(List.of("free", "pro")))).enumValues())
                .containsExactly("free", "pro");
        assertThat(service.create(command("name", AttributeDatatype.STRING, false, List.of("ignored"))).enumValues()).isEmpty();
    }

    @Test
    void only_string_and_number_attributes_can_be_hash_attributes() {
        assertThat(service.create(command("a", AttributeDatatype.STRING, true, null)).hashAttribute()).isTrue();
        assertThat(service.create(command("b", AttributeDatatype.NUMBER, true, null)).hashAttribute()).isTrue();

        for (AttributeDatatype type : List.of(AttributeDatatype.BOOLEAN, AttributeDatatype.STRING_ARRAY, AttributeDatatype.NUMBER_ARRAY)) {
            assertThatThrownBy(() -> service.create(command("c", type, true, null))).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void creating_rejects_invalid_and_duplicated_keys() {
        when(persistence.existsByKey("country")).thenReturn(true);

        assertThatThrownBy(() -> service.create(command("bad key", AttributeDatatype.STRING, false, null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(command("country", AttributeDatatype.STRING, false, null))).isInstanceOf(ConflictException.class);

        verify(persistence, never()).save(any());
    }

    @Test
    void updating_requires_the_current_version_and_valid_content() {
        Attribute updated = service.update("country", command("country", AttributeDatatype.STRING, true, null), 1L);

        assertThat(updated.hashAttribute()).isTrue();
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        verify(auditService).record(context, AuditAction.UPDATE, EntityType.ATTRIBUTE, "country", existing, updated);
        assertThatThrownBy(() -> service.update("country", command("country", AttributeDatatype.STRING, false, null), 0L))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update("country", command("country", AttributeDatatype.ENUM, false, null), 1L))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void lookups_report_unknown_attributes_and_index_by_key() {
        when(persistence.findByKey("ghost")).thenReturn(Optional.empty());
        when(persistence.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.get("ghost")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update("ghost", command("ghost", AttributeDatatype.STRING, false, null), 0L))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.findAll()).containsExactly(existing);
        assertThat(service.findAllByKey()).containsEntry("country", existing);
    }

    private static AttributeCommand command(String key, AttributeDatatype datatype, boolean hashAttribute, List<String> enumValues) {
        return new AttributeCommand(key, datatype, null, hashAttribute, false, enumValues, false);
    }
}
