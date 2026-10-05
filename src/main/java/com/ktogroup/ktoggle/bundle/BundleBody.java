package com.ktogroup.ktoggle.bundle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * The hashed and signed part of a bundle ({@value #CONTRACT_VERSION}). It is self-contained: given a bundle and a
 * set of attributes, any decision can be reproduced without looking at the current configuration.
 *
 * <p>Deliberately <b>not</b> part of the body (they live in the envelope or in the activation chain): creation
 * time, author, change id and the previous bundle. Hence identical configurations always produce the same hash.
 *
 * @param payload   exactly what SDKs receive under {@code features} (GrowthBook format)
 * @param sources   featureKey &rarr; feature revision the payload was compiled from
 * @param evaluator the evaluation semantics the payload was produced for, used to pick the replay engine
 */
public record BundleBody(
        String contractVersion,
        Target target,
        Evaluator evaluator,
        Map<String, Integer> sources,
        ObjectNode payload) {

    public static final String CONTRACT_VERSION = "ktoggle.bundle.v1";

    public record Target(String clientKey, String environment, List<String> projects) {
    }

    /**
     * @param spec              payload/evaluation spec ({@code growthbook-features}) and its hashing version
     * @param referenceEvaluator SDK used server-side (simulation, replay, contract tests) when the bundle was built
     */
    public record Evaluator(String spec, int hashVersion, String referenceEvaluator) {
    }
}
