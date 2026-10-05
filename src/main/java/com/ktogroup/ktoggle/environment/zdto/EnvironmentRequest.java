package com.ktogroup.ktoggle.environment.zdto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code key} is used on creation only (keys are immutable); {@code version} is required on update. */
public record EnvironmentRequest(
        String key,
        @NotBlank @Size(max = 200) String name,
        String description,
        int sortOrder,
        boolean requiresReview,
        Long version) {
}
