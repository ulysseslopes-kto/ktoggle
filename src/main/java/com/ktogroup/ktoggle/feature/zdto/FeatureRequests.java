package com.ktogroup.ktoggle.feature.zdto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.feature.ValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Request bodies of the feature API (changes to existing features use the draft API). */
public final class FeatureRequests {

    private FeatureRequests() {
    }

    public record CreateFeatureRequest(
            @NotBlank String key,
            String projectKey,
            @NotNull ValueType valueType,
            @NotNull JsonNode defaultValue,
            String description,
            String owner,
            List<String> tags) {
    }
}
