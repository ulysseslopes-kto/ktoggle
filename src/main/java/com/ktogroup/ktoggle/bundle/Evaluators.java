package com.ktogroup.ktoggle.bundle;

import growthbook.sdk.java.Version;

/** The evaluation semantics bundles are currently produced for. */
public final class Evaluators {

    public static final String GROWTHBOOK_SPEC = "growthbook-features";
    /** Hashing used by force/rollout rules (GrowthBook hash v1). */
    public static final int HASH_VERSION = 1;
    public static final String REFERENCE_EVALUATOR = "growthbook-sdk-java@" + Version.SDK_VERSION;

    private Evaluators() {
    }

    public static BundleBody.Evaluator current() {
        return new BundleBody.Evaluator(GROWTHBOOK_SPEC, HASH_VERSION, REFERENCE_EVALUATOR);
    }
}
