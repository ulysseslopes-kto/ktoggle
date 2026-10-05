package com.ktogroup.ktoggle.draft;

import com.ktogroup.ktoggle.config.SecurityConfiguration;
import com.ktogroup.ktoggle.draft.DraftMerger.MergeResult;
import com.ktogroup.ktoggle.draft.DraftMerger.SectionChange;
import com.ktogroup.ktoggle.environment.Environment;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Pure decision logic: which environments a draft affects, whether it needs a review, and who may do what. */
@Component
public class ReviewPolicy {

    /**
     * Environments whose SDK payload would change: environments with changed settings, plus — for changes to the
     * default value or archiving — every environment where the feature is enabled (live or after publishing).
     */
    public Set<String> affectedEnvironments(MergeResult merge, Map<String, EnvironmentSettings> live) {
        Set<String> affected = new TreeSet<>();
        boolean global = false;
        for (SectionChange change : merge.changes()) {
            if (change.environment() != null) {
                affected.add(change.environment());
            } else if ("defaultValue".equals(change.section()) || "archived".equals(change.section())) {
                global = true;
            }
        }
        if (global) {
            live.forEach((env, settings) -> {
                if (settings.enabled()) {
                    affected.add(env);
                }
            });
            merge.merged().environments().forEach((env, settings) -> {
                if (settings.enabled()) {
                    affected.add(env);
                }
            });
        }
        return affected;
    }

    public List<String> environmentsRequiringReview(Set<String> affected, Collection<Environment> environments) {
        Map<String, Environment> byKey = environments.stream().collect(Collectors.toMap(Environment::key, Function.identity()));
        return affected.stream().filter(env -> byKey.containsKey(env) && byKey.get(env).requiresReview()).toList();
    }

    public boolean canApprove(ReviewSettings settings, FeatureDraft draft, String username, Set<String> roles) {
        boolean eligible = settings.approverUsers().contains(username)
                || settings.approverRoles().stream().anyMatch(roles::contains);
        return eligible && (settings.allowSelfApproval() || !username.equals(draft.createdBy()));
    }

    public boolean canBypass(ReviewSettings settings, Set<String> roles) {
        return settings.bypassEnabled() && roles.contains(SecurityConfiguration.ADMIN);
    }

    /** Why publishing is blocked right now (empty = can publish). */
    public List<String> publishBlockers(FeatureDraft draft, MergeResult merge, List<String> reviewEnvironments) {
        List<String> blockers = new ArrayList<>();
        if (!draft.status().isOpen()) {
            blockers.add("O draft está " + draft.status());
        }
        if (!merge.conflicts().isEmpty()) {
            blockers.add("Conflitos com a versão no ar: " + merge.conflicts());
        }
        if (merge.changes().isEmpty()) {
            blockers.add("Nenhuma alteração em relação à versão no ar");
        }
        if (!reviewEnvironments.isEmpty() && draft.status() != DraftStatus.APPROVED) {
            blockers.add("Requer aprovação (afeta " + String.join(", ", reviewEnvironments) + ")");
        }
        return blockers;
    }
}
