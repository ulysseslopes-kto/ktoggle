package com.ktogroup.ktoggle.delivery.zout;

import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.delivery.DeliveryChannel;
import com.ktogroup.ktoggle.delivery.DeliveryLogEntry;
import com.ktogroup.ktoggle.delivery.DeliveryLogPersistencePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryLogJdbcAdapter implements DeliveryLogPersistencePort {

    private final JdbcTemplate jdbc;

    @Override
    public void upsert(List<DeliveryLogEntry> entries) {
        jdbc.batchUpdate("""
                        INSERT INTO delivery_log (id, client_key, bundle_hash, channel, pod, window_start, first_seen, last_seen,
                                                  deliveries, sdk_hint)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (client_key, bundle_hash, channel, pod, window_start) DO UPDATE SET
                            first_seen = LEAST(delivery_log.first_seen, EXCLUDED.first_seen),
                            last_seen  = GREATEST(delivery_log.last_seen, EXCLUDED.last_seen),
                            deliveries = delivery_log.deliveries + EXCLUDED.deliveries,
                            sdk_hint   = COALESCE(EXCLUDED.sdk_hint, delivery_log.sdk_hint)""",
                entries, entries.size(), (ps, e) -> {
                    ps.setObject(1, Ids.newId());
                    ps.setString(2, e.clientKey());
                    ps.setString(3, e.bundleHash());
                    ps.setString(4, e.channel().name());
                    ps.setString(5, e.pod());
                    ps.setTimestamp(6, Timestamp.from(e.windowStart()));
                    ps.setTimestamp(7, Timestamp.from(e.firstSeen()));
                    ps.setTimestamp(8, Timestamp.from(e.lastSeen()));
                    ps.setLong(9, e.deliveries());
                    ps.setString(10, e.sdkHint());
                });
    }

    @Override
    public List<DeliveryLogEntry> find(String clientKey, Instant from, Instant to, int limit) {
        return jdbc.query("""
                        SELECT client_key, bundle_hash, channel, pod, window_start, first_seen, last_seen, deliveries, sdk_hint
                          FROM delivery_log
                         WHERE client_key = ?
                           AND last_seen >= COALESCE(?, '-infinity'::timestamptz)
                           AND first_seen < COALESCE(?, 'infinity'::timestamptz)
                         ORDER BY last_seen DESC LIMIT ?""",
                (rs, row) -> new DeliveryLogEntry(rs.getString("client_key"), rs.getString("bundle_hash"),
                        DeliveryChannel.valueOf(rs.getString("channel")), rs.getString("pod"),
                        rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("first_seen").toInstant(),
                        rs.getTimestamp("last_seen").toInstant(), rs.getLong("deliveries"), rs.getString("sdk_hint")),
                clientKey, from == null ? null : Timestamp.from(from), to == null ? null : Timestamp.from(to), limit);
    }
}
