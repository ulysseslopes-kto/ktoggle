package com.ktogroup.ktoggle.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One link of the control-plane audit chain. {@code hash = sha256(JCS(hashableView()))}, and the view
 * contains {@code prevHash}, so altering or removing any past entry breaks every hash after it.
 */
public record AuditEntry(
        long seq,
        UUID id,
        UUID changeId,
        String actor,
        AuditAction action,
        EntityType entityType,
        String entityKey,
        JsonNode before,
        JsonNode after,
        String reason,
        Instant occurredAt,
        String prevHash,
        String hash) {

    public Map<String, Object> hashableView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", seq);
        view.put("id", id.toString());
        view.put("changeId", changeId.toString());
        view.put("actor", actor);
        view.put("action", action.name());
        view.put("entityType", entityType.name());
        view.put("entityKey", entityKey);
        view.put("before", before);
        view.put("after", after);
        view.put("reason", reason);
        view.put("occurredAt", occurredAt.toString());
        view.put("prevHash", prevHash);
        return view;
    }

    public AuditEntry withHash(String newHash) {
        return new AuditEntry(seq, id, changeId, actor, action, entityType, entityKey, before, after, reason, occurredAt,
                prevHash, newHash);
    }
}
