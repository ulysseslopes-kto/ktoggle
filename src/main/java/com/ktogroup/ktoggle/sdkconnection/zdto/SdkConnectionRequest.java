package com.ktogroup.ktoggle.sdkconnection.zdto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * {@code clientKey} (optional, e.g. to keep an imported GrowthBook key) and {@code environmentKey} are used on
 * creation only; {@code version} is required on update. On update, null {@code projectKeys}, {@code encryptPayload}
 * or {@code remoteEval} keep the current setting; an empty {@code projectKeys} means every project.
 */
public record SdkConnectionRequest(
        String clientKey,
        @NotBlank String name,
        String environmentKey,
        List<String> projectKeys,
        Boolean encryptPayload,
        Boolean remoteEval,
        Long version) {
}
