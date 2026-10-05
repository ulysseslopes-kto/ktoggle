package com.ktogroup.ktoggle.environment;

import java.time.Instant;

/** @param requiresReview publishing a draft that touches this environment needs an approval */
public record Environment(String key, String name, String description, int sortOrder, boolean requiresReview,
                          Instant createdAt, Instant updatedAt, Long version) {
}
