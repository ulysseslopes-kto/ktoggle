package com.ktogroup.ktoggle.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int VERIFY_PAGE = 500;

    private final AuditLogPersistencePort persistence;
    private final CanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Appends an entry to the chain. Must run inside the transaction of the change it describes, so the
     * change and its audit record commit (or roll back) together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry record(ChangeContext context, AuditAction action, EntityType entityType, String entityKey,
                             Object before, Object after) {
        persistence.lockChain();
        AuditEntry last = persistence.findLast().orElse(null);
        AuditEntry entry = new AuditEntry(
                last == null ? 1 : last.seq() + 1,
                Ids.newId(),
                context.changeId(),
                context.actor(),
                action,
                entityType,
                entityKey,
                toJson(before),
                toJson(after),
                context.reason(),
                Ids.now(clock),
                last == null ? null : last.hash(),
                null);
        AuditEntry hashed = entry.withHash(hash(entry));
        persistence.insert(hashed);
        return hashed;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> search(AuditQuery query) {
        return persistence.search(query);
    }

    /** Recomputes every hash and link from the genesis entry. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        long checked = 0;
        String expectedPrev = null;
        long afterSeq = 0;
        List<AuditEntry> page;
        do {
            page = persistence.findAfter(afterSeq, VERIFY_PAGE);
            for (AuditEntry entry : page) {
                checked++;
                if (entry.seq() != checked) {
                    return ChainVerification.broken(checked, entry.seq(), "Gap in sequence: expected " + checked);
                }
                if (!Objects.equals(expectedPrev, entry.prevHash())) {
                    return ChainVerification.broken(checked, entry.seq(), "prevHash does not link to previous entry");
                }
                if (!hash(entry).equals(entry.hash())) {
                    return ChainVerification.broken(checked, entry.seq(), "Entry content does not match its hash");
                }
                expectedPrev = entry.hash();
                afterSeq = entry.seq();
            }
        } while (page.size() == VERIFY_PAGE);
        return ChainVerification.ok(checked);
    }

    private String hash(AuditEntry entry) {
        return Hashes.sha256Hex(canonicalJson.canonicalize(entry.hashableView()));
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }
}
