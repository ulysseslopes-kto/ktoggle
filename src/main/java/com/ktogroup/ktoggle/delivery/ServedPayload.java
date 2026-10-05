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

    public String etag() {
        return "\"" + bundleHash + "\"";
    }

    public boolean remoteEval() {
        return SdkConnection.REMOTE_EVAL.equals(deliveryMode);
    }
}
