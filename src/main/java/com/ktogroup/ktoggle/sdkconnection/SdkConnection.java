package com.ktogroup.ktoggle.sdkconnection;

import java.time.Instant;
import java.util.List;

/**
 * What an SDK client key gets: the features of one environment, optionally restricted to some projects.
 *
 * @param pinnedBundleHash when set (emergency rollback), automatic publication is suspended for this connection
 */
public record SdkConnection(String clientKey, String name, String environmentKey, List<String> projectKeys,
                            String pinnedBundleHash, Instant createdAt, Instant updatedAt, Long version) {

    public boolean includesProject(String projectKey) {
        return projectKeys.isEmpty() || (projectKey != null && projectKeys.contains(projectKey));
    }
}
