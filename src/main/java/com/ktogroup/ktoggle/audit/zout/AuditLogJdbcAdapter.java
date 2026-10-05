package com.ktogroup.ktoggle.audit.zout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditEntry;
import com.ktogroup.ktoggle.audit.AuditLogPersistencePort;
import com.ktogroup.ktoggle.audit.AuditQuery;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.jdbc.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC on purpose: the audit chain needs an advisory lock and exact control over what is written,
 * and the table is append-only (UPDATE/DELETE are rejected by a trigger).
 */
@Component
@RequiredArgsConstructor
public class AuditLogJdbcAdapter implements AuditLogPersistencePort {

    /** Advisory lock key for the audit chain ("ktog" + 1). */
    private static final long AUDIT_CHAIN_LOCK = 0x6b746f67_00000001L;

    private static final String COLUMNS = """
            seq, id, change_id, actor, action, entity_type, entity_key, before_state::text AS before_state,
            after_state::text AS after_state, reason, occurred_at, prev_hash, hash""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void lockChain() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, AUDIT_CHAIN_LOCK);
    }

    @Override
    public Optional<AuditEntry> findLast() {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq DESC LIMIT 1", this::map).stream().findFirst();
    }

    @Override
    public void insert(AuditEntry e) {
        jdbc.update("""
                        INSERT INTO audit_log (seq, id, change_id, actor, action, entity_type, entity_key, before_state,
                                               after_state, reason, occurred_at, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)""",
                e.seq(), e.id(), e.changeId(), e.actor(), e.action().name(), e.entityType().name(), e.entityKey(),
                JsonColumns.write(objectMapper, e.before()), JsonColumns.write(objectMapper, e.after()), e.reason(),
                Timestamp.from(e.occurredAt()), e.prevHash(), e.hash());
    }

    @Override
    public List<AuditEntry> search(AuditQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM audit_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.entityType() != null) {
            sql.append(" AND entity_type = ?");
            args.add(q.entityType().name());
        }
        if (q.entityKey() != null) {
            sql.append(" AND entity_key = ?");
            args.add(q.entityKey());
        }
        if (q.actor() != null) {
            sql.append(" AND actor = ?");
            args.add(q.actor());
        }
        if (q.changeId() != null) {
            sql.append(" AND change_id = ?");
            args.add(q.changeId());
        }
        if (q.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(q.from()));
        }
        if (q.to() != null) {
            sql.append(" AND occurred_at < ?");
            args.add(Timestamp.from(q.to()));
        }
        if (q.beforeSeq() != null) {
            sql.append(" AND seq < ?");
            args.add(q.beforeSeq());
        }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        args.add(q.limit());
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public List<AuditEntry> findAfter(long afterSeq, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_log WHERE seq > ? ORDER BY seq LIMIT ?", this::map, afterSeq, limit);
    }

    private AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(
                rs.getLong("seq"),
                rs.getObject("id", UUID.class),
                rs.getObject("change_id", UUID.class),
                rs.getString("actor"),
                AuditAction.valueOf(rs.getString("action")),
                EntityType.valueOf(rs.getString("entity_type")),
                rs.getString("entity_key"),
                JsonColumns.read(objectMapper, rs.getString("before_state")),
                JsonColumns.read(objectMapper, rs.getString("after_state")),
                rs.getString("reason"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}
