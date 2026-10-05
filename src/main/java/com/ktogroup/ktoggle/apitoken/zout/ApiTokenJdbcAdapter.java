package com.ktogroup.ktoggle.apitoken.zout;

import com.ktogroup.ktoggle.apitoken.ApiToken;
import com.ktogroup.ktoggle.apitoken.ApiTokenPersistencePort;
import com.ktogroup.ktoggle.apitoken.ApiTokenRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiTokenJdbcAdapter implements ApiTokenPersistencePort {

    private static final String COLUMNS = """
            id, name, prefix, token_hash, role, created_by, created_at, expires_at, last_used_at, revoked_by, revoked_at""";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void insert(ApiToken t) {
        jdbc.update("INSERT INTO api_token (" + COLUMNS + """
                ) VALUES (:id, :name, :prefix, :hash, :role, :createdBy, :createdAt, :expiresAt, NULL, NULL, NULL)""",
                new MapSqlParameterSource("id", t.id()).addValue("name", t.name()).addValue("prefix", t.prefix())
                        .addValue("hash", t.tokenHash()).addValue("role", t.role().name()).addValue("createdBy", t.createdBy())
                        .addValue("createdAt", timestamp(t.createdAt())).addValue("expiresAt", timestamp(t.expiresAt())));
    }

    @Override
    public Optional<ApiToken> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_token WHERE id = :id", new MapSqlParameterSource("id", id),
                ApiTokenJdbcAdapter::token).stream().findFirst();
    }

    @Override
    public Optional<ApiToken> findByHash(String tokenHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_token WHERE token_hash = :hash",
                new MapSqlParameterSource("hash", tokenHash), ApiTokenJdbcAdapter::token).stream().findFirst();
    }

    @Override
    public boolean existsActiveByName(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM api_token WHERE name = :name AND revoked_at IS NULL)",
                new MapSqlParameterSource("name", name), Boolean.class));
    }

    @Override
    public List<ApiToken> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_token ORDER BY revoked_at IS NOT NULL, created_at DESC",
                ApiTokenJdbcAdapter::token);
    }

    @Override
    public void revoke(UUID id, String revokedBy, Instant revokedAt) {
        jdbc.update("UPDATE api_token SET revoked_by = :by, revoked_at = :at WHERE id = :id AND revoked_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("by", revokedBy).addValue("at", timestamp(revokedAt)));
    }

    @Override
    public void touch(UUID id, Instant lastUsedAt) {
        jdbc.update("UPDATE api_token SET last_used_at = :at WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("at", timestamp(lastUsedAt)));
    }

    private static ApiToken token(ResultSet rs, int row) throws SQLException {
        return new ApiToken(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("prefix"),
                rs.getString("token_hash"), ApiTokenRole.valueOf(rs.getString("role")), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at"), rs.getString("revoked_by"),
                instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
