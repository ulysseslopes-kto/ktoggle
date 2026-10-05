package com.ktogroup.ktoggle.draft.zout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.jdbc.JsonColumns;
import com.ktogroup.ktoggle.draft.DraftEvent;
import com.ktogroup.ktoggle.draft.DraftPersistencePort;
import com.ktogroup.ktoggle.draft.DraftStatus;
import com.ktogroup.ktoggle.draft.FeatureDraft;
import com.ktogroup.ktoggle.draft.ReviewSettings;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DraftJdbcAdapter implements DraftPersistencePort {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String DRAFT_COLUMNS = """
            id, feature_key, title, base_revision, status, proposed::text AS proposed, created_by, created_at, updated_by,
            updated_at, published_revision, version""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<FeatureDraft> findById(UUID id) {
        return jdbc.query("SELECT " + DRAFT_COLUMNS + " FROM feature_draft WHERE id = :id",
                new MapSqlParameterSource("id", id), this::draft).stream().findFirst();
    }

    @Override
    public List<FeatureDraft> findByFeature(String featureKey, Collection<DraftStatus> statuses) {
        return jdbc.query("SELECT " + DRAFT_COLUMNS
                        + " FROM feature_draft WHERE feature_key = :key AND status IN (:statuses) ORDER BY updated_at DESC",
                new MapSqlParameterSource("key", featureKey).addValue("statuses", names(statuses)), this::draft);
    }

    @Override
    public List<FeatureDraft> findByStatus(Collection<DraftStatus> statuses, int limit) {
        return jdbc.query("SELECT " + DRAFT_COLUMNS
                        + " FROM feature_draft WHERE status IN (:statuses) ORDER BY updated_at DESC LIMIT :limit",
                new MapSqlParameterSource("statuses", names(statuses)).addValue("limit", limit), this::draft);
    }

    @Override
    public FeatureDraft save(FeatureDraft d) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("featureKey", d.featureKey())
                .addValue("title", d.title())
                .addValue("baseRevision", d.baseRevision())
                .addValue("status", d.status().name())
                .addValue("proposed", JsonColumns.write(objectMapper, d.proposed()))
                .addValue("createdBy", d.createdBy())
                .addValue("createdAt", Timestamp.from(d.createdAt()))
                .addValue("updatedBy", d.updatedBy())
                .addValue("updatedAt", Timestamp.from(d.updatedAt()))
                .addValue("publishedRevision", d.publishedRevision())
                .addValue("version", d.version());
        if (d.version() == null) {
            jdbc.update("""
                    INSERT INTO feature_draft (id, feature_key, title, base_revision, status, proposed, created_by, created_at,
                                               updated_by, updated_at, published_revision, version)
                    VALUES (:id, :featureKey, :title, :baseRevision, :status, CAST(:proposed AS jsonb), :createdBy, :createdAt,
                            :updatedBy, :updatedAt, :publishedRevision, 0)""", params);
        } else {
            int updated = jdbc.update("""
                    UPDATE feature_draft SET title = :title, base_revision = :baseRevision, status = :status,
                           proposed = CAST(:proposed AS jsonb), updated_by = :updatedBy, updated_at = :updatedAt,
                           published_revision = :publishedRevision, version = version + 1
                     WHERE id = :id AND version = :version""", params);
            if (updated == 0) {
                throw ConflictException.staleVersion("Draft", d.id().toString(), d.version(), -1);
            }
        }
        return findById(d.id()).orElseThrow();
    }

    @Override
    public void insertEvent(DraftEvent e) {
        jdbc.update("""
                INSERT INTO draft_event (id, draft_id, type, actor, comment, occurred_at)
                VALUES (:id, :draftId, :type, :actor, :comment, :occurredAt)""",
                new MapSqlParameterSource().addValue("id", e.id()).addValue("draftId", e.draftId())
                        .addValue("type", e.type().name()).addValue("actor", e.actor()).addValue("comment", e.comment())
                        .addValue("occurredAt", Timestamp.from(e.occurredAt())));
    }

    @Override
    public List<DraftEvent> events(UUID draftId) {
        return jdbc.query("SELECT * FROM draft_event WHERE draft_id = :id ORDER BY occurred_at, id",
                new MapSqlParameterSource("id", draftId),
                (rs, row) -> new DraftEvent(rs.getObject("id", UUID.class), rs.getObject("draft_id", UUID.class),
                        DraftEvent.Type.valueOf(rs.getString("type")), rs.getString("actor"), rs.getString("comment"),
                        rs.getTimestamp("occurred_at").toInstant()));
    }

    @Override
    public ReviewSettings reviewSettings() {
        return jdbc.queryForObject("""
                SELECT approver_roles::text AS approver_roles, approver_users::text AS approver_users, allow_self_approval,
                       reset_review_on_change, bypass_enabled, updated_at, updated_by, version
                  FROM review_settings WHERE id = 1""", new MapSqlParameterSource(), (rs, row) -> new ReviewSettings(
                strings(rs.getString("approver_roles")), strings(rs.getString("approver_users")),
                rs.getBoolean("allow_self_approval"), rs.getBoolean("reset_review_on_change"), rs.getBoolean("bypass_enabled"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"), rs.getLong("version")));
    }

    @Override
    public ReviewSettings saveReviewSettings(ReviewSettings s) {
        int updated = jdbc.update("""
                UPDATE review_settings SET approver_roles = CAST(:roles AS jsonb), approver_users = CAST(:users AS jsonb),
                       allow_self_approval = :self, reset_review_on_change = :reset, bypass_enabled = :bypass,
                       updated_at = :updatedAt, updated_by = :updatedBy, version = version + 1
                 WHERE id = 1 AND version = :version""", new MapSqlParameterSource()
                .addValue("roles", JsonColumns.write(objectMapper, s.approverRoles()))
                .addValue("users", JsonColumns.write(objectMapper, s.approverUsers()))
                .addValue("self", s.allowSelfApproval()).addValue("reset", s.resetReviewOnChange())
                .addValue("bypass", s.bypassEnabled()).addValue("updatedAt", Timestamp.from(s.updatedAt()))
                .addValue("updatedBy", s.updatedBy()).addValue("version", s.version()));
        if (updated == 0) {
            throw ConflictException.staleVersion("Review settings", "review", s.version(), -1);
        }
        return reviewSettings();
    }

    private FeatureDraft draft(ResultSet rs, int row) throws SQLException {
        try {
            Integer published = rs.getObject("published_revision", Integer.class);
            return new FeatureDraft(rs.getObject("id", UUID.class), rs.getString("feature_key"), rs.getString("title"),
                    rs.getInt("base_revision"), DraftStatus.valueOf(rs.getString("status")),
                    objectMapper.readValue(rs.getString("proposed"), FeatureSnapshot.class), rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("updated_by"),
                    rs.getTimestamp("updated_at").toInstant(), published, rs.getLong("version"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted draft " + rs.getString("id"), e);
        }
    }

    private List<String> strings(String json) {
        try {
            return objectMapper.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted review settings", e);
        }
    }

    private static List<String> names(Collection<DraftStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }
}
