package com.ktogroup.ktoggle.commons.change;

import java.util.UUID;

/**
 * Who is changing the configuration and why. One {@code changeId} groups every audit entry, revision
 * and bundle activation caused by the same request, which is what links a bundle back to its author.
 */
public record ChangeContext(UUID changeId, String actor, String reason) {
}
