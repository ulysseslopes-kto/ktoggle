package com.ktogroup.ktoggle.savedgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/**
 * Reusable audience. {@link SavedGroupType#LIST}: {@code attributeKey IN values};
 * {@link SavedGroupType#CONDITION}: an arbitrary targeting condition.
 */
public record SavedGroup(String key, String name, String description, SavedGroupType type, String attributeKey,
                         List<JsonNode> values, JsonNode condition, Instant createdAt, Instant updatedAt, Long version) {

    /** The condition this group stands for, inlined into rule conditions at compile time. */
    public JsonNode toCondition() {
        if (type == SavedGroupType.CONDITION) {
            return condition;
        }
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        ArrayNode array = in.putArray("$in");
        values.forEach(array::add);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set(attributeKey, in);
        return result;
    }
}
