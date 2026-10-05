package com.ktogroup.ktoggle.bundle;

/** Write-once external copy of bundles (S3 Object Lock), independent from the service database. */
public interface BundleArchive {

    /** Stores the bundle (envelope + canonical content) and returns its location. Must be idempotent. */
    String store(Bundle bundle);
}
