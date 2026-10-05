package com.ktogroup.ktoggle.project.zout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "project")
public class ProjectEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "editor_roles", nullable = false)
    private List<String> editorRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "editor_users", nullable = false)
    private List<String> editorUsers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
