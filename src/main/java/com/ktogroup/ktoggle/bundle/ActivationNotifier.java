package com.ktogroup.ktoggle.bundle;

/**
 * Broadcasts "client key X now serves bundle Y" to every pod (Redis pub/sub in deployed environments,
 * in-process in tests). Delivery is best effort: pods also resynchronize periodically from the database.
 */
public interface ActivationNotifier {

    void notifyActivated(String clientKey, String bundleHash);
}
