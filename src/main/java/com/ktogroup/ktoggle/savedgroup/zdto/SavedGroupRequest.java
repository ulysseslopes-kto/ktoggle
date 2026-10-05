package com.ktogroup.ktoggle.savedgroup.zdto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SavedGroupRequest(
        String key,
        @NotBlank String name,
        String description,
        @NotNull SavedGroupType type,
        String attributeKey,
        List<JsonNode> values,
        JsonNode condition,
        Long version) {

    public SavedGroupCommand toCommand(String resolvedKey) {
        return new SavedGroupCommand(resolvedKey, name, description, type, attributeKey, values, condition);
    }
}
