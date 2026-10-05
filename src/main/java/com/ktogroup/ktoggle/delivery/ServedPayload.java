package com.ktogroup.ktoggle.delivery;

import java.time.Instant;

/** What a client key serves right now: a pre-rendered, verified GrowthBook-format response. */
public record ServedPayload(String clientKey, String bundleHash, long activationPosition, Instant activatedAt, String body) {

    public String etag() {
        return "\"" + bundleHash + "\"";
    }
}
