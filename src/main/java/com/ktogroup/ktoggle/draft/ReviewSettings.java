package com.ktogroup.ktoggle.draft;

import java.time.Instant;
import java.util.List;

/**
 * Who may approve drafts, configured by admins. Which environments need an approval is set per environment
 * ({@code requiresReview}).
 *
 * @param approverRoles       Keycloak realm roles whose holders may approve (e.g. ktoggle-admin, ktoggle-approver)
 * @param approverUsers       usernames allowed to approve regardless of their roles
 * @param allowSelfApproval   the author of a draft may approve it (and therefore publish it alone)
 * @param resetReviewOnChange editing an approved/pending draft sends it back for review
 * @param bypassEnabled       admins may publish without approval in an emergency (reason required, audited)
 */
public record ReviewSettings(List<String> approverRoles, List<String> approverUsers, boolean allowSelfApproval,
                             boolean resetReviewOnChange, boolean bypassEnabled, Instant updatedAt, String updatedBy,
                             Long version) {

    public ReviewSettings {
        approverRoles = approverRoles == null ? List.of() : List.copyOf(approverRoles);
        approverUsers = approverUsers == null ? List.of() : List.copyOf(approverUsers);
    }
}
