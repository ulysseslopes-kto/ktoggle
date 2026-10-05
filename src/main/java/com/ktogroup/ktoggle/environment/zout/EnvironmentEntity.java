package com.ktogroup.ktoggle.environment.zout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "environment")
public class EnvironmentEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
