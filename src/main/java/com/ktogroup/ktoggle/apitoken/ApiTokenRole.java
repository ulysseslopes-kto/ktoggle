package com.ktogroup.ktoggle.apitoken;

/**
 * What a token may do. Deliberately no admin: a token can never change the catalog, the review settings or other
 * tokens, never publishes without approval, and never counts as a reviewer, so four-eyes stays human.
 */
public enum ApiTokenRole {
    /** Read everything, simulate and replay. */
    VIEWER("ktoggle-viewer"),
    /** Also create and edit drafts, request review and publish where no approval is required. */
    EDITOR("ktoggle-editor");

    private final String realmRole;

    ApiTokenRole(String realmRole) {
        this.realmRole = realmRole;
    }

    /** The Keycloak realm role it maps to, so the same authorization rules apply to tokens and people. */
    public String realmRole() {
        return realmRole;
    }
}
