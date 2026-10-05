package com.ktogroup.ktoggle.decision.zout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.commons.jdbc.JsonColumns;
import com.ktogroup.ktoggle.decision.DecisionEvent;
import com.ktogroup.ktoggle.decision.DecisionEventPersistencePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DecisionEventJdbcAdapter implements DecisionEventPersistencePort {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final Pattern PARTITION = Pattern.compile("^decision_event_(\\d{4})_(\\d{2})$");
    private static final String COLUMNS = """
            event_id, client_key, bundle_hash, feature_key, value::text AS value, rule_id, source, sdk, occurred_at,
            received_at, attributes::text AS attributes, attributes_digest, digest_key_id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void insertBatch(List<DecisionEvent> events) {
        jdbc.batchUpdate("""
                        INSERT INTO decision_event (event_id, client_key, bundle_hash, feature_key, value, rule_id, source, sdk,
                                                    occurred_at, received_at, attributes, attributes_digest, digest_key_id)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        ON CONFLICT DO NOTHING""",
                events, events.size(), (ps, e) -> {
                    ps.setObject(1, e.eventId());
                    ps.setString(2, e.clientKey());
                    ps.setString(3, e.bundleHash());
                    ps.setString(4, e.featureKey());
                    ps.setString(5, JsonColumns.write(objectMapper, e.value()));
                    ps.setString(6, e.ruleId());
                    ps.setString(7, e.source());
                    ps.setString(8, e.sdk());
                    ps.setTimestamp(9, Timestamp.from(e.occurredAt()));
                    ps.setTimestamp(10, Timestamp.from(e.receivedAt()));
                    ps.setString(11, JsonColumns.write(objectMapper, e.attributes()));
                    ps.setString(12, e.attributesDigest());
                    ps.setString(13, e.digestKeyId());
                });
    }

    @Override
    public List<DecisionEvent> search(DecisionQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM decision_event WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.clientKey() != null) {
            sql.append(" AND client_key = ?");
            args.add(q.clientKey());
        }
        if (q.featureKey() != null) {
            sql.append(" AND feature_key = ?");
            args.add(q.featureKey());
        }
        if (q.bundleHash() != null) {
            sql.append(" AND bundle_hash = ?");
            args.add(q.bundleHash());
        }
        if (q.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(q.from()));
        }
        if (q.to() != null) {
            sql.append(" AND occurred_at < ?");
            args.add(Timestamp.from(q.to()));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        args.add(q.limit());
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public Optional<DecisionEvent> findById(UUID eventId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM decision_event WHERE event_id = ? LIMIT 1", this::map, eventId)
                .stream().findFirst();
    }

    @Override
    public void ensureMonthlyPartition(YearMonth month) {
        String from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        String to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        // Identifiers and bounds come from YearMonth formatting only (no user input).
        jdbc.execute("CREATE TABLE IF NOT EXISTS decision_event_%s PARTITION OF decision_event FOR VALUES FROM ('%s') TO ('%s')"
                .formatted(month.format(SUFFIX), from, to));
    }

    @Override
    public List<String> dropPartitionsBefore(YearMonth month) {
        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                  JOIN pg_class p ON p.oid = i.inhparent
                 WHERE p.relname = 'decision_event'""", String.class);
        List<String> dropped = new ArrayList<>();
        for (String partition : partitions) {
            Matcher m = PARTITION.matcher(partition);
            if (m.matches() && YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).isBefore(month)) {
                jdbc.execute("DROP TABLE " + partition);
                dropped.add(partition);
            }
        }
        return dropped;
    }

    private DecisionEvent map(ResultSet rs, int row) throws SQLException {
        JsonNode attributes = JsonColumns.read(objectMapper, rs.getString("attributes"));
        return new DecisionEvent(rs.getObject("event_id", UUID.class), rs.getString("client_key"), rs.getString("bundle_hash"),
                rs.getString("feature_key"), JsonColumns.read(objectMapper, rs.getString("value")), rs.getString("rule_id"),
                rs.getString("source"), rs.getString("sdk"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("received_at").toInstant(), (ObjectNode) attributes, rs.getString("attributes_digest"),
                rs.getString("digest_key_id"));
    }
}
