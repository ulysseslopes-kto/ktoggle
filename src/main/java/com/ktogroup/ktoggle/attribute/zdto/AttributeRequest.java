package com.ktogroup.ktoggle.attribute.zdto;

import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.attribute.AttributeService.AttributeCommand;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code pii} defaults to true: an attribute is treated as personal data unless explicitly declared otherwise
 * (privacy by default).
 */
public record AttributeRequest(
        String key,
        @NotNull AttributeDatatype datatype,
        String description,
        boolean hashAttribute,
        Boolean pii,
        List<String> enumValues,
        boolean archived,
        Long version) {

    public AttributeCommand toCommand(String resolvedKey) {
        return new AttributeCommand(resolvedKey, datatype, description, hashAttribute, pii == null || pii, enumValues, archived);
    }
}
