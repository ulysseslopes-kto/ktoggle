package com.ktogroup.ktoggle.sdkconnection.zdto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * {@code clientKey} (optional, e.g. to keep an imported GrowthBook key) and {@code environmentKey} are used on
 * creation only; {@code version} is required on update.
 */
public record SdkConnectionRequest(
        String clientKey,
        @NotBlank String name,
        String environmentKey,
        List<String> projectKeys,
        Long version) {
}
