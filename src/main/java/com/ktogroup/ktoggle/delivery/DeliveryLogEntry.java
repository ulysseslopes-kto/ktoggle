package com.ktogroup.ktoggle.delivery;

import java.time.Instant;

public record DeliveryLogEntry(String clientKey, String bundleHash, DeliveryChannel channel, String pod,
                               Instant windowStart, Instant firstSeen, Instant lastSeen, long deliveries, String sdkHint) {
}
