package com.ktogroup.ktoggle.growthbook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to an existing GrowthBook, used only by the importer and the shadow comparison. Nothing is configured by
 * default: until {@code apiHost} is set, ktoggle never calls GrowthBook.
 *
 * @param apiHost   GrowthBook API host, e.g. {@code https://growthbook-api.example.com} (REST API and SDK endpoint)
 * @param sdkHost   where SDK payloads are read for the shadow comparison (GrowthBook Proxy, CDN); defaults to apiHost
 * @param secretKey read-only secret API key (REST API: projects, features, saved groups...); never logged or returned
 */
@ConfigurationProperties("ktoggle.growthbook")
public record GrowthBookProperties(String apiHost, String sdkHost, String secretKey, Shadow shadow) {

    public GrowthBookProperties {
        apiHost = blankToNull(apiHost);
        sdkHost = blankToNull(sdkHost) == null ? apiHost : sdkHost;
        secretKey = blankToNull(secretKey);
        shadow = shadow == null ? new Shadow(false, null, 0, 0) : shadow;
    }

    public boolean configured() {
        return apiHost != null;
    }

    /**
     * @param enabled    run the comparison periodically (it can always be run on demand)
     * @param interval   time between scheduled comparisons
     * @param samples    simulated users evaluated per client key and run
     * @param readyAfter consecutive clean runs before a connection is reported ready to migrate
     */
    public record Shadow(boolean enabled, Duration interval, int samples, int readyAfter) {

        public Shadow {
            interval = interval == null ? Duration.ofMinutes(15) : interval;
            samples = samples <= 0 ? 2000 : samples;
            readyAfter = readyAfter <= 0 ? 3 : readyAfter;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip().replaceAll("/+$", "");
    }
}
