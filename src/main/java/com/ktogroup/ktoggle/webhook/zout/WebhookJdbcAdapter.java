package com.ktogroup.ktoggle.webhook.zout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.jdbc.JsonColumns;
import com.ktogroup.ktoggle.webhook.Webhook;
import com.ktogroup.ktoggle.webhook.WebhookDelivery;
import com.ktogroup.ktoggle.webhook.WebhookEvent;
import com.ktogroup.ktoggle.webhook.WebhookPersistencePort;
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
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WebhookJdbcAdapter implements WebhookPersistencePort {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String WEBHOOK_COLUMNS = """
            id, name, url, format, events::text AS events, secret, enabled, created_by, created_at, updated_by, updated_at, version""";
    private static final String DELIVERY_COLUMNS = """
            id, webhook_id, event, payload::text AS payload, status, attempts, next_attempt_at, last_status_code, last_error,
            created_at, delivered_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public List<Webhook> findAll() {
        return jdbc.query("SELECT " + WEBHOOK_COLUMNS + " FROM webhook ORDER BY created_at", this::webhook);
    }

    @Override
    public Optional<Webhook> findById(UUID id) {
        return jdbc.query("SELECT " + WEBHOOK_COLUMNS + " FROM webhook WHERE id = :id", new MapSqlParameterSource("id", id),
                this::webhook).stream().findFirst();
    }

    @Override
    public void insert(Webhook w) {
        jdbc.update("""
                INSERT INTO webhook (id, name, url, format, events, secret, enabled, created_by, created_at, updated_by, updated_at,
                                     version)
                VALUES (:id, :name, :url, :format, CAST(:events AS jsonb), :secret, :enabled, :createdBy, :createdAt, :updatedBy,
                        :updatedAt, :version)""", params(w));
    }

    @Override
    public boolean update(Webhook w, long expectedVersion) {
        return jdbc.update("""
                UPDATE webhook SET name = :name, url = :url, format = :format, events = CAST(:events AS jsonb), enabled = :enabled,
                                   updated_by = :updatedBy, updated_at = :updatedAt, version = :version
                WHERE id = :id AND version = :expected""", params(w).addValue("expected", expectedVersion)) == 1;
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM webhook WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public void insertDelivery(WebhookDelivery d) {
        jdbc.update("""
                INSERT INTO webhook_delivery (id, webhook_id, event, payload, status, attempts, next_attempt_at, created_at)
                VALUES (:id, :webhookId, :event, CAST(:payload AS jsonb), :status, :attempts, :next, :createdAt)""",
                new MapSqlParameterSource("id", d.id()).addValue("webhookId", d.webhookId()).addValue("event", d.event().name())
                        .addValue("payload", JsonColumns.write(objectMapper, d.payload())).addValue("status", d.status().name())
                        .addValue("attempts", d.attempts()).addValue("next", timestamp(d.nextAttemptAt()))
                        .addValue("createdAt", timestamp(d.createdAt())));
    }

    @Override
    @Transactional
    public List<WebhookDelivery> claimDue(Instant now, Instant lockedUntil, int limit) {
        return jdbc.query("""
                UPDATE webhook_delivery SET status = 'SENDING', locked_until = :lockedUntil
                WHERE id IN (
                    SELECT id FROM webhook_delivery
                    WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
                       OR (status = 'SENDING' AND locked_until < :now)
                    ORDER BY next_attempt_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING""" + " " + DELIVERY_COLUMNS,
                new MapSqlParameterSource("now", timestamp(now)).addValue("lockedUntil", timestamp(lockedUntil))
                        .addValue("limit", limit), this::delivery);
    }

    @Override
    public void markDelivered(UUID id, int statusCode, Instant deliveredAt) {
        jdbc.update("""
                UPDATE webhook_delivery SET status = 'DELIVERED', attempts = attempts + 1, last_status_code = :code,
                                            last_error = NULL, delivered_at = :at, locked_until = NULL
                WHERE id = :id""", new MapSqlParameterSource("id", id).addValue("code", statusCode)
                .addValue("at", timestamp(deliveredAt)));
    }

    @Override
    public void markFailed(UUID id, Integer statusCode, String error, int attempts, Instant nextAttemptAt, boolean giveUp) {
        jdbc.update("""
                UPDATE webhook_delivery SET status = :status, attempts = :attempts, last_status_code = :code, last_error = :error,
                                            next_attempt_at = :next, locked_until = NULL
                WHERE id = :id""", new MapSqlParameterSource("id", id).addValue("status", giveUp ? "FAILED" : "RETRY")
                .addValue("attempts", attempts).addValue("code", statusCode).addValue("error", error)
                .addValue("next", timestamp(nextAttemptAt)));
    }

    @Override
    public List<WebhookDelivery> findDeliveries(UUID webhookId, int limit) {
        return jdbc.query("SELECT " + DELIVERY_COLUMNS
                        + " FROM webhook_delivery WHERE webhook_id = :id ORDER BY created_at DESC LIMIT :limit",
                new MapSqlParameterSource("id", webhookId).addValue("limit", limit), this::delivery);
    }

    @Override
    public int deleteDeliveriesBefore(Instant before) {
        return jdbc.update("DELETE FROM webhook_delivery WHERE created_at < :before AND status IN ('DELIVERED', 'FAILED')",
                new MapSqlParameterSource("before", timestamp(before)));
    }

    private MapSqlParameterSource params(Webhook w) {
        return new MapSqlParameterSource("id", w.id()).addValue("name", w.name()).addValue("url", w.url())
                .addValue("format", w.format().name())
                .addValue("events", JsonColumns.write(objectMapper, w.events().stream().map(Enum::name).toList()))
                .addValue("secret", w.secret()).addValue("enabled", w.enabled()).addValue("createdBy", w.createdBy())
                .addValue("createdAt", timestamp(w.createdAt())).addValue("updatedBy", w.updatedBy())
                .addValue("updatedAt", timestamp(w.updatedAt())).addValue("version", w.version());
    }

    private Webhook webhook(ResultSet rs, int row) throws SQLException {
        List<String> events;
        try {
            events = objectMapper.readValue(rs.getString("events"), STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupted webhook events", e);
        }
        return new Webhook(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                Webhook.Format.valueOf(rs.getString("format")), events.stream().map(WebhookEvent::fromCode).toList(),
                rs.getString("secret"), rs.getBoolean("enabled"), rs.getString("created_by"), instant(rs, "created_at"),
                rs.getString("updated_by"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private WebhookDelivery delivery(ResultSet rs, int row) throws SQLException {
        return new WebhookDelivery(rs.getObject("id", UUID.class), rs.getObject("webhook_id", UUID.class),
                WebhookEvent.fromCode(rs.getString("event")), JsonColumns.read(objectMapper, rs.getString("payload")),
                WebhookDelivery.Status.valueOf(rs.getString("status")), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
                rs.getObject("last_status_code", Integer.class), rs.getString("last_error"), instant(rs, "created_at"),
                instant(rs, "delivered_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
