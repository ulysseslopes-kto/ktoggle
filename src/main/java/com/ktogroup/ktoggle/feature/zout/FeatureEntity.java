package com.ktogroup.ktoggle.feature.zout;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.feature.ValueType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "feature")
public class FeatureEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String key;

    @Column(name = "project_key")
    private String projectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, updatable = false)
    private ValueType valueType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_value", nullable = false)
    private JsonNode defaultValue;

    private String description;

    private String owner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> tags;

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false)
    private int revision;

    @OneToMany(mappedBy = "feature", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<FeatureEnvironmentEntity> environments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Version
    private Long version;
}
