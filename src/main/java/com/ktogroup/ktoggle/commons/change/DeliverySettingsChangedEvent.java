package com.ktogroup.ktoggle.commons.change;

/**
 * How a client key's payload is rendered changed (e.g. encryption turned on or the key rotated) while the bundle it
 * serves did not. Every pod must re-render and push it to connected SDKs.
 */
public record DeliverySettingsChangedEvent(String clientKey) {
}
