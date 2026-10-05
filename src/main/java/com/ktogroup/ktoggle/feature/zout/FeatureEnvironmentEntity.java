package com.ktogroup.ktoggle.feature.zout;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "feature_environment")
public class FeatureEnvironmentEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false, updatable = false)
    private FeatureEntity feature;

    @Column(name = "environment_key", nullable = false, updatable = false)
    private String environmentKey;

    @Column(nullable = false)
    private boolean enabled;

    /** Ordered list of rules (polymorphic, see {@link com.ktogroup.ktoggle.feature.Rule}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode rules;
}
