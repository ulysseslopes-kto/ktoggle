package com.ktogroup.ktoggle.sdkconnection.zout;

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
@Table(name = "sdk_connection")
public class SdkConnectionEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "client_key", nullable = false, unique = true, updatable = false)
    private String clientKey;

    @Column(name = "environment_key", nullable = false, updatable = false)
    private String environmentKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "project_keys", nullable = false)
    private List<String> projectKeys;

    @Column(name = "pinned_bundle_hash")
    private String pinnedBundleHash;

    @Column(name = "encrypt_payload", nullable = false)
    private boolean encryptPayload;

    @Column(name = "decryption_key")
    private String decryptionKey;

    @Column(name = "remote_eval", nullable = false)
    private boolean remoteEval;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
