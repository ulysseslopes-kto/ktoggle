package com.ktogroup.ktoggle.project.zdto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code key} is used on creation only (keys are immutable); {@code version} is required on update. Leaving both editor
 * lists empty keeps the project open to every editor.
 */
public record ProjectRequest(
        String key,
        @NotBlank @Size(max = 200) String name,
        String description,
        List<String> editorRoles,
        List<String> editorUsers,
        Long version) {
}
