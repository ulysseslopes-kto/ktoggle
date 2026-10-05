package com.ktogroup.ktoggle.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AuditLogPersistencePort persistence = mock(AuditLogPersistencePort.class);
    private final List<AuditEntry> chain = new ArrayList<>();
    private final ChangeContext context = new ChangeContext(Ids.newId(), "alice", "because");
    private final AuditService service = new AuditService(persistence, new CanonicalJson(mapper), mapper,
            Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(persistence.findLast()).thenAnswer(invocation -> chain.isEmpty() ? Optional.empty() : Optional.of(chain.getLast()));
        org.mockito.Mockito.doAnswer(invocation -> chain.add(invocation.getArgument(0))).when(persistence).insert(org.mockito.ArgumentMatchers.any());
        when(persistence.findAfter(anyLong(), anyInt())).thenAnswer(invocation -> {
            long after = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return chain.stream().filter(e -> e.seq() > after).limit(limit).toList();
        });
    }

    @Test
    void entries_form_a_hash_chain_starting_at_the_genesis_entry() {
        AuditEntry first = append("one");
        AuditEntry second = append("two");

        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.prevHash()).isNull();
        assertThat(first.hash()).hasSize(64);
        assertThat(second.seq()).isEqualTo(2);
        assertThat(second.prevHash()).isEqualTo(first.hash());
        assertThat(second.hash()).isNotEqualTo(first.hash());
        verify(persistence, org.mockito.Mockito.times(2)).lockChain();
    }

    @Test
    void entries_record_who_why_and_the_before_and_after_images() {
        AuditEntry entry = service.record(context, AuditAction.UPDATE, EntityType.PROJECT, "p", Map.of("name", "old"), Map.of("name", "new"));

        assertThat(entry.actor()).isEqualTo("alice");
        assertThat(entry.reason()).isEqualTo("because");
        assertThat(entry.changeId()).isEqualTo(context.changeId());
        assertThat(entry.before().path("name").asText()).isEqualTo("old");
        assertThat(entry.after().path("name").asText()).isEqualTo("new");
        assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
    }

    @Test
    void creations_and_deletions_have_no_before_or_after_image() {
        AuditEntry created = service.record(context, AuditAction.CREATE, EntityType.PROJECT, "p", null, Map.of("name", "new"));
        AuditEntry deleted = service.record(context, AuditAction.DELETE, EntityType.PROJECT, "p", Map.of("name", "new"), null);

        assertThat(created.before()).isNull();
        assertThat(deleted.after()).isNull();
    }

    @Test
    void an_untouched_chain_verifies() {
        append("one");
        append("two");
        append("three");

        ChainVerification verification = service.verifyChain();

        assertThat(verification.valid()).isTrue();
        assertThat(verification.checked()).isEqualTo(3);
        assertThat(verification.brokenAt()).isNull();
    }

    @Test
    void an_empty_chain_verifies() {
        assertThat(service.verifyChain()).isEqualTo(ChainVerification.ok(0));
    }

    @Test
    void a_long_chain_is_verified_across_pages() {
        for (int i = 0; i < 1001; i++) {
            append("entity-" + i);
        }

        assertThat(service.verifyChain()).isEqualTo(ChainVerification.ok(1001));
    }

    @Test
    void a_removed_entry_is_reported_as_a_gap() {
        append("one");
        append("two");
        append("three");
        chain.remove(1);

        ChainVerification verification = service.verifyChain();

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(3);
        assertThat(verification.checked()).isEqualTo(2);
        assertThat(verification.message()).startsWith("Gap in sequence");
    }

    @Test
    void an_entry_that_does_not_link_to_its_predecessor_is_reported() {
        append("one");
        AuditEntry second = append("two");
        chain.set(1, new AuditEntry(second.seq(), second.id(), second.changeId(), second.actor(), second.action(), second.entityType(),
                second.entityKey(), second.before(), second.after(), second.reason(), second.occurredAt(), "f".repeat(64), second.hash()));

        ChainVerification verification = service.verifyChain();

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(2);
        assertThat(verification.message()).contains("prevHash does not link");
    }

    @Test
    void an_edited_entry_is_reported_because_its_hash_no_longer_matches() {
        append("one");
        AuditEntry second = append("two");
        chain.set(1, new AuditEntry(second.seq(), second.id(), second.changeId(), "mallory", second.action(), second.entityType(),
                second.entityKey(), second.before(), second.after(), second.reason(), second.occurredAt(), second.prevHash(), second.hash()));

        ChainVerification verification = service.verifyChain();

        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAt()).isEqualTo(2);
        assertThat(verification.message()).contains("does not match its hash");
    }

    @Test
    void searching_delegates_to_the_store() {
        AuditQuery query = new AuditQuery(EntityType.PROJECT, "p", null, null, null, null, null, 10);
        when(persistence.search(query)).thenReturn(List.of());

        assertThat(service.search(query)).isEmpty();
        verify(persistence).search(query);
    }

    private AuditEntry append(String entityKey) {
        return service.record(context, AuditAction.CREATE, EntityType.PROJECT, entityKey, null, Map.of("key", entityKey));
    }
}
