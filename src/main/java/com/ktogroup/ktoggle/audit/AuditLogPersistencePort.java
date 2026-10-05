package com.ktogroup.ktoggle.audit;

import java.util.List;
import java.util.Optional;

public interface AuditLogPersistencePort {

    /** Serializes chain appends until the current transaction ends (cluster-wide). */
    void lockChain();

    Optional<AuditEntry> findLast();

    void insert(AuditEntry entry);

    List<AuditEntry> search(AuditQuery query);

    /** Entries with {@code seq > afterSeq}, ascending, at most {@code limit}. Used to walk the chain. */
    List<AuditEntry> findAfter(long afterSeq, int limit);
}
