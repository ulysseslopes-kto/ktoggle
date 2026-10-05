package com.ktogroup.ktoggle.attribute;

import java.time.Instant;
import java.util.List;

/**
 * An SDK attribute that targeting conditions can reference (e.g. {@code userId}, {@code country}).
 *
 * @param hashAttribute may be used to bucket users in percentage rollouts
 * @param pii           personal data (LGPD): never stored in clear in decision events, only inside the HMAC digest
 */
public record Attribute(String key, AttributeDatatype datatype, String description, boolean hashAttribute, boolean pii,
                        List<String> enumValues, boolean archived, Instant createdAt, Instant updatedAt, Long version) {
}
