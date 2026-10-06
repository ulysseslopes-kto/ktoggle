package com.ktogroup.ktoggle.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import java.time.Instant;

/**
 * What a client key serves right now: a pre-rendered, verified GrowthBook-format response.
 *
 * @param deliveryMode how {@code body} was rendered ({@code plain}, encrypted with a given key, or {@code remote-eval}),
 *                     see SdkConnection
 * @param features     the verified clear-text features of the bundle (what remote evaluation evaluates); never sent as is
 *                     to remote-eval or encrypted connections
 */
public record ServedPayload(String clientKey, String bundleHash, long activationPosition, Instant activatedAt,
                            String deliveryMode, String body, JsonNode features) {

    public ServedPayload(String clientKey, String bundleHash, long activationPosition, Instant activatedAt,
                         String deliveryMode, String body) {
        this(clientKey, bundleHash, activationPosition, activatedAt, deliveryMode, body, null);
    }

    /**
     * The body depends on the bundle and on how it is delivered: turning encryption on or off, rotating the key or
     * switching remote evaluation must change the ETag, or SDKs holding the old body would get a 304 and keep a body
     * they can no longer use. The delivery mode carries the key fingerprint only, never the key.
     */
    public String etag() {
        return SdkConnection.PLAIN.equals(deliveryMode) ? "\"" + bundleHash + "\""
                : "\"" + bundleHash + "-" + deliveryMode.replace(':', '-') + "\"";
    }

    public boolean remoteEval() {
        return SdkConnection.REMOTE_EVAL.equals(deliveryMode);
    }
}
