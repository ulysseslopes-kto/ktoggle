package com.ktogroup.ktoggle.attribute.zout;

import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "attribute")
public class AttributeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttributeDatatype datatype;

    private String description;

    @Column(name = "hash_attribute", nullable = false)
    private boolean hashAttribute;

    @Column(nullable = false)
    private boolean pii;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_values")
    private List<String> enumValues;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
