package com.ktogroup.ktoggle.delivery;

import java.time.Instant;

/**
 * What a client key serves right now: a pre-rendered, verified GrowthBook-format response.
 *
 * @param deliveryMode how {@code body} was rendered ({@code plain} or encrypted with a given key), see SdkConnection
 */
public record ServedPayload(String clientKey, String bundleHash, long activationPosition, Instant activatedAt,
                            String deliveryMode, String body) {

    public String etag() {
        return "\"" + bundleHash + "\"";
    }
}
