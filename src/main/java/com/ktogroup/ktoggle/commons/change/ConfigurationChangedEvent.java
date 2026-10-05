package com.ktogroup.ktoggle.commons.change;

/**
 * Published (in-transaction) by every service that changes something that may end up in an SDK payload.
 * The bundle publisher consumes it after commit to compile, sign and activate new bundles.
 */
public record ConfigurationChangedEvent(ChangeContext context) {
}
