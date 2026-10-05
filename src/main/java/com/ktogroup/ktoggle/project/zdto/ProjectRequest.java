package com.ktogroup.ktoggle.project.zdto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code key} is used on creation only (keys are immutable); {@code version} is required on update. */
public record ProjectRequest(
        String key,
        @NotBlank @Size(max = 200) String name,
        String description,
        Long version) {
}
