package com.ktogroup.ktoggle.bundle;

/** Local (in-pod) event raised when a notification about a new activation is received. */
public record BundleActivatedEvent(String clientKey, String bundleHash) {
}
