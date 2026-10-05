package com.ktogroup.ktoggle.feature.zout;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only (a database trigger rejects UPDATE/DELETE). */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "feature_revision")
public class FeatureRevisionEntity {

    @Id
    private UUID id;

    @Column(name = "feature_id", nullable = false)
    private UUID featureId;

    @Column(name = "feature_key", nullable = false)
    private String featureKey;

    @Column(nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode snapshot;

    @Column(name = "change_id", nullable = false)
    private UUID changeId;

    private String comment;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
