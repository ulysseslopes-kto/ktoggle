package com.ktogroup.ktoggle.decision;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param digestKeyId    identifies {@code digestSecret} so it can be rotated (old digests keep their key id)
 * @param digestSecret   HMAC secret (base64) for attribute digests — from Secrets Manager in stg/prd
 * @param retentionMonths monthly partitions older than this are dropped (LGPD data minimisation)
 */
@ConfigurationProperties("ktoggle.decisions")
public record DecisionProperties(String digestKeyId, String digestSecret, int queueCapacity, int batchSize,
                                 Duration maxPastAge, Duration maxFutureSkew, int maxEventsPerRequest,
                                 int retentionMonths) {

    public DecisionProperties {
        digestKeyId = digestKeyId == null || digestKeyId.isBlank() ? "k1" : digestKeyId;
        queueCapacity = queueCapacity <= 0 ? 50_000 : queueCapacity;
        batchSize = batchSize <= 0 ? 500 : batchSize;
        maxPastAge = maxPastAge == null ? Duration.ofDays(7) : maxPastAge;
        maxFutureSkew = maxFutureSkew == null ? Duration.ofMinutes(5) : maxFutureSkew;
        maxEventsPerRequest = maxEventsPerRequest <= 0 ? 1_000 : maxEventsPerRequest;
        retentionMonths = retentionMonths <= 0 ? 13 : retentionMonths;
    }
}
