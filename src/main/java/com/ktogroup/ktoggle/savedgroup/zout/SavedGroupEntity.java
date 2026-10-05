package com.ktogroup.ktoggle.savedgroup.zout;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "saved_group")
public class SavedGroupEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SavedGroupType type;

    @Column(name = "attribute_key")
    private String attributeKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "list_values")
    private JsonNode listValues;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode condition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
