package com.ktogroup.ktoggle.audit;

import java.time.Instant;
import java.util.UUID;

/** Keyset-paginated filter: entries with {@code seq < beforeSeq}, newest first. All filters optional. */
public record AuditQuery(
        EntityType entityType,
        String entityKey,
        String actor,
        UUID changeId,
        Instant from,
        Instant to,
        Long beforeSeq,
        int limit) {
}
