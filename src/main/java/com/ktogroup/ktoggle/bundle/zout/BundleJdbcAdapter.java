package com.ktogroup.ktoggle.bundle.zout;

import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleActivation.Kind;
import com.ktogroup.ktoggle.bundle.BundlePersistencePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code bundle} and {@code bundle_activation} are append-only (UPDATE/DELETE rejected by triggers). */
@Component
@RequiredArgsConstructor
public class BundleJdbcAdapter implements BundlePersistencePort {

    /** Advisory lock key for bundle publication ("ktog" + 2). */
    private static final long PUBLICATION_LOCK = 0x6b746f67_00000002L;

    private static final String BUNDLE_COLUMNS = """
            hash, contract_version, client_key, environment_key, content, payload_hash, signature_alg, key_id, signature,
            created_at, created_by""";
    private static final String ACTIVATION_COLUMNS = """
            id, client_key, position, bundle_hash, kind, change_id, activated_by, activated_at, reason, prev_hash, hash""";

    private final JdbcTemplate jdbc;

    @Override
    public Optional<Bundle> findByHash(String hash) {
        return jdbc.query("SELECT " + BUNDLE_COLUMNS + " FROM bundle WHERE hash = ?", BundleJdbcAdapter::bundle, hash)
                .stream().findFirst();
    }

    @Override
    public void insertIfAbsent(Bundle b) {
        jdbc.update("INSERT INTO bundle (" + BUNDLE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (hash) DO NOTHING",
                b.hash(), b.contractVersion(), b.clientKey(), b.environmentKey(), b.content(), b.payloadHash(),
                b.signatureAlg(), b.keyId(), b.signature(), Timestamp.from(b.createdAt()), b.createdBy());
    }

    @Override
    public List<Bundle> findByClientKey(String clientKey, int limit) {
        return jdbc.query("SELECT " + BUNDLE_COLUMNS + " FROM bundle WHERE client_key = ? ORDER BY created_at DESC LIMIT ?",
                BundleJdbcAdapter::bundle, clientKey, limit);
    }

    @Override
    public void lockPublication() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, PUBLICATION_LOCK);
    }

    @Override
    public Optional<BundleActivation> findLastActivation(String clientKey) {
        return jdbc.query("SELECT " + ACTIVATION_COLUMNS + " FROM bundle_activation WHERE client_key = ? ORDER BY position DESC LIMIT 1",
                BundleJdbcAdapter::activation, clientKey).stream().findFirst();
    }

    @Override
    public List<BundleActivation> findCurrentActivations() {
        return jdbc.query("SELECT DISTINCT ON (client_key) " + ACTIVATION_COLUMNS
                + " FROM bundle_activation ORDER BY client_key, position DESC", BundleJdbcAdapter::activation);
    }

    @Override
    public Optional<BundleActivation> findActivationAt(String clientKey, Instant instant) {
        return jdbc.query("SELECT " + ACTIVATION_COLUMNS + """
                         FROM bundle_activation WHERE client_key = ? AND activated_at <= ?
                        ORDER BY activated_at DESC, position DESC LIMIT 1""",
                BundleJdbcAdapter::activation, clientKey, Timestamp.from(instant)).stream().findFirst();
    }

    @Override
    public void insertActivation(BundleActivation a) {
        jdbc.update("INSERT INTO bundle_activation (" + ACTIVATION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.id(), a.clientKey(), a.position(), a.bundleHash(), a.kind().name(), a.changeId(), a.activatedBy(),
                Timestamp.from(a.activatedAt()), a.reason(), a.prevHash(), a.hash());
    }

    @Override
    public List<BundleActivation> findActivations(String clientKey, int limit) {
        return jdbc.query("SELECT " + ACTIVATION_COLUMNS + " FROM bundle_activation WHERE client_key = ? ORDER BY position DESC LIMIT ?",
                BundleJdbcAdapter::activation, clientKey, limit);
    }

    @Override
    public List<BundleActivation> findActivationsAfter(String clientKey, long afterPosition, int limit) {
        return jdbc.query("SELECT " + ACTIVATION_COLUMNS
                        + " FROM bundle_activation WHERE client_key = ? AND position > ? ORDER BY position LIMIT ?",
                BundleJdbcAdapter::activation, clientKey, afterPosition, limit);
    }

    @Override
    public List<Bundle> findNotArchived(int limit) {
        return jdbc.query("""
                        SELECT b.* FROM bundle b LEFT JOIN bundle_archive a ON a.bundle_hash = b.hash
                        WHERE a.bundle_hash IS NULL ORDER BY b.created_at LIMIT ?""",
                BundleJdbcAdapter::bundle, limit);
    }

    @Override
    public void markArchived(String bundleHash, String location, Instant archivedAt) {
        jdbc.update("INSERT INTO bundle_archive (bundle_hash, location, archived_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                bundleHash, location, Timestamp.from(archivedAt));
    }

    private static Bundle bundle(ResultSet rs, int row) throws SQLException {
        return new Bundle(rs.getString("hash"), rs.getString("contract_version"), rs.getString("client_key"),
                rs.getString("environment_key"), rs.getString("content"), rs.getString("payload_hash"),
                rs.getString("signature_alg"), rs.getString("key_id"), rs.getString("signature"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"));
    }

    private static BundleActivation activation(ResultSet rs, int row) throws SQLException {
        return new BundleActivation(rs.getObject("id", UUID.class), rs.getString("client_key"), rs.getLong("position"),
                rs.getString("bundle_hash"), Kind.valueOf(rs.getString("kind")), rs.getObject("change_id", UUID.class),
                rs.getString("activated_by"), rs.getTimestamp("activated_at").toInstant(), rs.getString("reason"),
                rs.getString("prev_hash"), rs.getString("hash"));
    }
}
