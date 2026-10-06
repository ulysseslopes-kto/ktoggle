package com.ktogroup.ktoggle.growthbook.zout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.jdbc.JsonColumns;
import com.ktogroup.ktoggle.growthbook.ShadowComparator.Divergence;
import com.ktogroup.ktoggle.growthbook.ShadowRun;
import com.ktogroup.ktoggle.growthbook.ShadowRunPersistencePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShadowRunJdbcAdapter implements ShadowRunPersistencePort {

    private static final TypeReference<List<Divergence>> DIVERGENCES = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, client_key, started_at, duration_ms, status, bundle_hash, samples, features_compared, divergent_features,
            divergences::text AS divergences, error, triggered_by""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void insert(ShadowRun r) {
        jdbc.update("""
                INSERT INTO shadow_run (id, client_key, started_at, duration_ms, status, bundle_hash, samples, features_compared,
                                        divergent_features, divergences, error, triggered_by)
                VALUES (:id, :clientKey, :startedAt, :durationMs, :status, :bundleHash, :samples, :featuresCompared,
                        :divergentFeatures, CAST(:divergences AS jsonb), :error, :triggeredBy)""",
                new MapSqlParameterSource("id", r.id()).addValue("clientKey", r.clientKey())
                        .addValue("startedAt", Timestamp.from(r.startedAt())).addValue("durationMs", r.durationMs())
                        .addValue("status", r.status().name()).addValue("bundleHash", r.bundleHash())
                        .addValue("samples", r.samples()).addValue("featuresCompared", r.featuresCompared())
                        .addValue("divergentFeatures", r.divergentFeatures())
                        .addValue("divergences", JsonColumns.write(objectMapper, r.divergences()))
                        .addValue("error", r.error()).addValue("triggeredBy", r.triggeredBy()));
    }

    @Override
    public List<ShadowRun> findByClientKey(String clientKey, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shadow_run WHERE client_key = :key ORDER BY started_at DESC LIMIT :limit",
                new MapSqlParameterSource("key", clientKey).addValue("limit", limit), this::run);
    }

    @Override
    public int deleteBefore(Instant before) {
        return jdbc.update("DELETE FROM shadow_run WHERE started_at < :before", new MapSqlParameterSource("before", Timestamp.from(before)));
    }

    private ShadowRun run(ResultSet rs, int row) throws SQLException {
        List<Divergence> divergences;
        try {
            divergences = objectMapper.readValue(rs.getString("divergences"), DIVERGENCES);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupted shadow run", e);
        }
        return new ShadowRun(rs.getObject("id", UUID.class), rs.getString("client_key"), rs.getTimestamp("started_at").toInstant(),
                rs.getLong("duration_ms"), ShadowRun.Status.valueOf(rs.getString("status")), rs.getString("bundle_hash"),
                rs.getInt("samples"), rs.getInt("features_compared"), rs.getInt("divergent_features"), divergences,
                rs.getString("error"), rs.getString("triggered_by"));
    }
}
