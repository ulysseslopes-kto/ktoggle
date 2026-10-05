package com.ktogroup.ktoggle.bundle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One link of the per-connection activation chain: "from {@code activatedAt} on, {@code clientKey} serves
 * {@code bundleHash}". {@code hash = sha256(JCS(hashableView()))} and the view includes {@code prevHash}.
 */
public record BundleActivation(
        UUID id,
        String clientKey,
        long position,
        String bundleHash,
        Kind kind,
        UUID changeId,
        String activatedBy,
        Instant activatedAt,
        String reason,
        String prevHash,
        String hash) {

    public enum Kind {
        /** Automatic publication after a configuration change. */
        PUBLISH,
        /** Emergency rollback to a previous bundle (the connection gets pinned). */
        ROLLBACK
    }

    public Map<String, Object> hashableView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id.toString());
        view.put("clientKey", clientKey);
        view.put("position", position);
        view.put("bundleHash", bundleHash);
        view.put("kind", kind.name());
        view.put("changeId", changeId.toString());
        view.put("activatedBy", activatedBy);
        view.put("activatedAt", activatedAt.toString());
        view.put("reason", reason);
        view.put("prevHash", prevHash);
        return view;
    }

    public BundleActivation withHash(String newHash) {
        return new BundleActivation(id, clientKey, position, bundleHash, kind, changeId, activatedBy, activatedAt, reason,
                prevHash, newHash);
    }
}
