package com.ktogroup.ktoggle.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.ForbiddenException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.security.CurrentUser;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.config.SecurityConfiguration;
import com.ktogroup.ktoggle.draft.DraftEvent.Type;
import com.ktogroup.ktoggle.draft.DraftMerger.MergeResult;
import com.ktogroup.ktoggle.draft.DraftMerger.SectionChange;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.Prerequisite;
import com.ktogroup.ktoggle.feature.Rule;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft workflow, modelled on GrowthBook: every change to an existing feature is staged in a draft, reviewed as a
 * diff against what is live, approved when an affected environment requires it (four-eyes, configurable), and only
 * then published as one revision. Admins may bypass the approval in an emergency, with a mandatory reason that is
 * flagged in the audit trail.
 */
@Service
@RequiredArgsConstructor
public class DraftService {

    private static final Set<DraftStatus> OPEN = EnumSet.of(DraftStatus.DRAFT, DraftStatus.PENDING_REVIEW,
            DraftStatus.CHANGES_REQUESTED, DraftStatus.APPROVED);

    private final DraftPersistencePort persistence;
    private final DraftMerger merger;
    private final ReviewPolicy policy;
    private final FeatureService featureService;
    private final EnvironmentService environmentService;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final CurrentUser currentUser;
    private final Clock clock;

    // ---- Queries -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DraftView view(UUID id) {
        return toView(get(id));
    }

    @Transactional(readOnly = true)
    public List<FeatureDraft> openDrafts(String featureKey) {
        return persistence.findByFeature(featureKey, OPEN);
    }

    @Transactional(readOnly = true)
    public List<FeatureDraft> byStatus(Set<DraftStatus> statuses, int limit) {
        return persistence.findByStatus(statuses.isEmpty() ? OPEN : statuses, limit);
    }

    // ---- Editing -------------------------------------------------------------------------------

    @Transactional
    public FeatureDraft create(String featureKey, String title) {
        Feature live = featureService.get(featureKey);
        return create(live, live.snapshot(), title);
    }

    /** "Revert": a draft whose proposal is the content of a past revision. */
    @Transactional
    public FeatureDraft revertTo(String featureKey, int revision) {
        Feature live = featureService.get(featureKey);
        FeatureSnapshot snapshot = featureService.revision(featureKey, revision).snapshot();
        return create(live, snapshot, "Revert to revision #" + revision);
    }

    @Transactional
    public FeatureDraft updateEnvironment(UUID id, String environmentKey, boolean enabled, List<Rule> rules, long version) {
        FeatureDraft draft = editable(id, version);
        environmentService.requireExists(environmentKey);
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i) == null) {
                    throw ValidationException.of("rules[%d] is null".formatted(i));
                }
            }
        }
        Map<String, EnvironmentSettings> environments = new HashMap<>(draft.proposed().environments());
        environments.put(environmentKey, new EnvironmentSettings(enabled, rules));
        FeatureSnapshot proposed = featureService.validate(draft.proposed().valueType(),
                draft.proposed().withEnvironments(environments));
        return touch(draft, proposed, "Environment %s: %s, %d rule(s)".formatted(environmentKey, enabled ? "on" : "off",
                proposed.environments().get(environmentKey).rules().size()));
    }

    @Transactional
    public FeatureDraft updateMetadata(UUID id, String projectKey, JsonNode defaultValue, String description, String owner,
                                       List<String> tags, boolean archived, long version) {
        FeatureDraft draft = editable(id, version);
        FeatureSnapshot p = draft.proposed();
        FeatureSnapshot proposed = featureService.validate(p.valueType(), new FeatureSnapshot(p.key(), projectKey, p.valueType(),
                defaultValue, description, owner, tags == null ? List.of() : tags, archived, p.prerequisites(), p.environments()));
        return touch(draft, proposed, "General settings changed");
    }

    @Transactional
    public FeatureDraft updatePrerequisites(UUID id, List<Prerequisite> prerequisites, long version) {
        FeatureDraft draft = editable(id, version);
        FeatureSnapshot proposed = featureService.validate(draft.proposed().valueType(),
                draft.proposed().withPrerequisites(Prerequisite.normalize(prerequisites)));
        return touch(draft, proposed, proposed.prerequisites().isEmpty() ? "Prerequisites removed"
                : "Prerequisites: " + String.join(", ", proposed.prerequisites().stream().map(Prerequisite::featureKey).toList()));
    }

    @Transactional
    public FeatureDraft rename(UUID id, String title, long version) {
        FeatureDraft draft = editable(id, version);
        return persistence.save(draft.withTitle(title).withUpdatedBy(currentUser.username()).withUpdatedAt(Ids.now(clock)));
    }

    /**
     * Brings the draft up to date with what is live: non-conflicting live changes are absorbed; conflicting
     * sections keep the draft's value ({@code keepDraft}) or take the live one.
     */
    @Transactional
    public FeatureDraft rebase(UUID id, boolean keepDraft, long version) {
        FeatureDraft draft = editable(id, version);
        Feature live = featureService.get(draft.featureKey());
        FeatureSnapshot base = featureService.revision(draft.featureKey(), draft.baseRevision()).snapshot();
        FeatureSnapshot resolved = merger.resolve(base, live.snapshot(), draft.proposed(), keepDraft);
        FeatureDraft rebased = draft.withBaseRevision(live.revision());
        return touch(rebased, resolved, "Updated with live revision #%d (%s on conflicts)"
                .formatted(live.revision(), keepDraft ? "keeping the draft" : "keeping what is live"), Type.REBASED);
    }

    // ---- Review --------------------------------------------------------------------------------

    @Transactional
    public FeatureDraft requestReview(UUID id, String comment) {
        FeatureDraft draft = open(id);
        if (draft.status() == DraftStatus.PENDING_REVIEW || draft.status() == DraftStatus.APPROVED) {
            throw new ConflictException(MessageCode.VALIDATION_ERROR, "Draft is already " + draft.status());
        }
        return transition(draft, DraftStatus.PENDING_REVIEW, Type.REVIEW_REQUESTED, comment);
    }

    @Transactional
    public FeatureDraft approve(UUID id, String comment) {
        FeatureDraft draft = reviewable(id);
        return transition(draft, DraftStatus.APPROVED, Type.APPROVED, comment);
    }

    @Transactional
    public FeatureDraft requestChanges(UUID id, String comment) {
        if (comment == null || comment.isBlank()) {
            throw ValidationException.of("Explain which changes are needed");
        }
        FeatureDraft draft = reviewable(id);
        return transition(draft, DraftStatus.CHANGES_REQUESTED, Type.CHANGES_REQUESTED, comment);
    }

    @Transactional
    public DraftEvent comment(UUID id, String comment) {
        if (comment == null || comment.isBlank()) {
            throw ValidationException.of("Comment is empty");
        }
        get(id);
        DraftEvent event = new DraftEvent(Ids.newId(), id, Type.COMMENTED, currentUser.username(), comment.strip(), Ids.now(clock));
        persistence.insertEvent(event);
        return event;
    }

    @Transactional
    public FeatureDraft discard(UUID id) {
        FeatureDraft draft = open(id);
        if (!draft.createdBy().equals(currentUser.username()) && !currentUser.hasRole(SecurityConfiguration.ADMIN)) {
            throw new ForbiddenException(MessageCode.NOT_ALLOWED, "Only the author or an admin can discard a draft");
        }
        return transition(draft, DraftStatus.DISCARDED, Type.DISCARDED, null);
    }

    // ---- Publication ---------------------------------------------------------------------------

    /**
     * Publishes the draft: merged with what is live (conflicts block), checked against the review policy, then
     * made live as one revision. With {@code bypass}, an admin publishes without approval; the reason header is
     * mandatory and the audit entry is {@link AuditAction#BYPASS_PUBLISH_DRAFT}.
     */
    @Transactional
    public FeatureDraft publish(UUID id, boolean bypass) {
        FeatureDraft draft = open(id);
        ChangeContext context = changeContextProvider.current();
        Feature live = featureService.get(draft.featureKey());
        MergeResult merge = merge(draft, live);
        if (!merge.conflicts().isEmpty()) {
            throw new ConflictException(MessageCode.DRAFT_CONFLICT,
                    "Draft conflicts with revision #%d published meanwhile: %s".formatted(live.revision(), merge.conflicts()));
        }
        if (merge.changes().isEmpty()) {
            throw new ConflictException(MessageCode.NOTHING_TO_PUBLISH, "Draft has no changes compared to the live feature");
        }
        List<String> reviewEnvironments = reviewEnvironments(merge, live);
        boolean approved = draft.status() == DraftStatus.APPROVED;
        if (!reviewEnvironments.isEmpty() && !approved) {
            if (!bypass) {
                throw new ForbiddenException(MessageCode.REVIEW_REQUIRED,
                        "Publishing to %s requires an approved review".formatted(String.join(", ", reviewEnvironments)));
            }
            if (!policy.canBypass(persistence.reviewSettings(), currentUser.roles())) {
                throw new ForbiddenException(MessageCode.NOT_ALLOWED, "Emergency publication is not allowed for this user");
            }
            if (context.reason() == null) {
                throw ValidationException.of("An emergency publication requires a reason (header %s)"
                        .formatted(ChangeContextProvider.REASON_HEADER));
            }
        }
        boolean bypassed = !reviewEnvironments.isEmpty() && !approved;
        Feature published = featureService.publish(draft.featureKey(), merge.merged(),
                bypassed ? AuditAction.BYPASS_PUBLISH_DRAFT : AuditAction.PUBLISH_DRAFT);
        FeatureDraft done = persistence.save(draft.withStatus(DraftStatus.PUBLISHED).withPublishedRevision(published.revision())
                .withUpdatedBy(context.actor()).withUpdatedAt(Ids.now(clock)));
        event(done, bypassed ? Type.BYPASS_PUBLISHED : Type.PUBLISHED,
                "Revision #%d%s".formatted(published.revision(), context.reason() == null ? "" : " — " + context.reason()));
        return done;
    }

    // ---- Settings ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReviewSettings reviewSettings() {
        return persistence.reviewSettings();
    }

    @Transactional
    public ReviewSettings updateReviewSettings(ReviewSettings requested, long version) {
        ReviewSettings current = persistence.reviewSettings();
        if (!Objects.equals(current.version(), version)) {
            throw ConflictException.staleVersion("Review settings", "review", version, current.version());
        }
        if (requested.approverRoles().isEmpty() && requested.approverUsers().isEmpty()) {
            throw ValidationException.of("At least one approver role or user is required");
        }
        ChangeContext context = changeContextProvider.current();
        ReviewSettings saved = persistence.saveReviewSettings(new ReviewSettings(requested.approverRoles(),
                requested.approverUsers(), requested.allowSelfApproval(), requested.resetReviewOnChange(),
                requested.bypassEnabled(), Ids.now(clock), context.actor(), current.version()));
        auditService.record(context, AuditAction.UPDATE, EntityType.REVIEW_SETTINGS, "review", current, saved);
        return saved;
    }

    // ---- Internals -----------------------------------------------------------------------------

    private FeatureDraft create(Feature live, FeatureSnapshot proposed, String title) {
        String user = currentUser.username();
        Instant now = Ids.now(clock);
        FeatureDraft draft = persistence.save(new FeatureDraft(Ids.newId(), live.key(),
                title == null || title.isBlank() ? null : title.strip(), live.revision(), DraftStatus.DRAFT,
                featureService.validate(live.valueType(), proposed), user, now, user, now, null, null));
        event(draft, Type.CREATED, draft.title());
        return draft;
    }

    private FeatureDraft touch(FeatureDraft draft, FeatureSnapshot proposed, String summary) {
        return touch(draft, proposed, summary, Type.UPDATED);
    }

    private FeatureDraft touch(FeatureDraft draft, FeatureSnapshot proposed, String summary, Type type) {
        boolean changed = !proposed.equals(draft.proposed()) || type == Type.REBASED;
        DraftStatus status = draft.status();
        boolean resetReview = changed && persistence.reviewSettings().resetReviewOnChange()
                && (status == DraftStatus.APPROVED || status == DraftStatus.CHANGES_REQUESTED);
        FeatureDraft saved = persistence.save(draft.withProposed(proposed)
                .withStatus(resetReview ? DraftStatus.PENDING_REVIEW : status)
                .withUpdatedBy(currentUser.username()).withUpdatedAt(Ids.now(clock)));
        event(saved, type, summary);
        if (resetReview && status == DraftStatus.APPROVED) {
            event(saved, Type.REVIEW_RESET, "Changed after approval: a new review is required");
        }
        return saved;
    }

    private FeatureDraft transition(FeatureDraft draft, DraftStatus status, Type type, String comment) {
        FeatureDraft saved = persistence.save(draft.withStatus(status).withUpdatedBy(currentUser.username())
                .withUpdatedAt(Ids.now(clock)));
        event(saved, type, comment == null || comment.isBlank() ? null : comment.strip());
        return saved;
    }

    private void event(FeatureDraft draft, Type type, String comment) {
        persistence.insertEvent(new DraftEvent(Ids.newId(), draft.id(), type, currentUser.username(), comment, Ids.now(clock)));
    }

    private FeatureDraft reviewable(UUID id) {
        FeatureDraft draft = open(id);
        if (draft.status() != DraftStatus.PENDING_REVIEW) {
            throw new ConflictException(MessageCode.VALIDATION_ERROR, "Only drafts pending review can be reviewed");
        }
        if (!policy.canApprove(persistence.reviewSettings(), draft, currentUser.username(), currentUser.roles())) {
            throw new ForbiddenException(MessageCode.NOT_ALLOWED, draft.createdBy().equals(currentUser.username())
                    ? "Authors cannot review their own drafts" : "You are not an approver");
        }
        return draft;
    }

    private FeatureDraft editable(UUID id, long version) {
        FeatureDraft draft = open(id);
        if (!Objects.equals(draft.version(), version)) {
            throw ConflictException.staleVersion("Draft", id.toString(), version, draft.version());
        }
        return draft;
    }

    private FeatureDraft open(UUID id) {
        FeatureDraft draft = get(id);
        if (!draft.status().isOpen()) {
            throw new ConflictException(MessageCode.DRAFT_CLOSED, "Draft is " + draft.status());
        }
        return draft;
    }

    private FeatureDraft get(UUID id) {
        return persistence.findById(id).orElseThrow(() -> new NotFoundException("Draft", id.toString()));
    }

    private MergeResult merge(FeatureDraft draft, Feature live) {
        FeatureSnapshot base = featureService.revision(draft.featureKey(), draft.baseRevision()).snapshot();
        return merger.merge(base, live.snapshot(), draft.proposed());
    }

    private List<String> reviewEnvironments(MergeResult merge, Feature live) {
        return policy.environmentsRequiringReview(policy.affectedEnvironments(merge, live.environments()),
                environmentService.findAll());
    }

    private DraftView toView(FeatureDraft draft) {
        Feature live = featureService.get(draft.featureKey());
        MergeResult merge = merge(draft, live);
        ReviewSettings settings = persistence.reviewSettings();
        List<String> reviewEnvironments = reviewEnvironments(merge, live);
        String user = currentUser.username();
        Set<String> roles = currentUser.roles();
        boolean open = draft.status().isOpen();
        boolean canEdit = open && (roles.contains(SecurityConfiguration.EDITOR) || roles.contains(SecurityConfiguration.ADMIN));
        List<String> blockers = policy.publishBlockers(draft, merge, reviewEnvironments);
        boolean canBypass = open && !reviewEnvironments.isEmpty() && draft.status() != DraftStatus.APPROVED
                && merge.conflicts().isEmpty() && !merge.changes().isEmpty() && policy.canBypass(settings, roles);
        return new DraftView(draft, live.revision(), merge.changes(), merge.conflicts(), reviewEnvironments,
                persistence.events(draft.id()), new DraftView.Permissions(
                canEdit,
                canEdit && (draft.status() == DraftStatus.DRAFT || draft.status() == DraftStatus.CHANGES_REQUESTED),
                open && draft.status() == DraftStatus.PENDING_REVIEW && policy.canApprove(settings, draft, user, roles),
                canEdit && blockers.isEmpty(),
                canBypass,
                open && (draft.createdBy().equals(user) || roles.contains(SecurityConfiguration.ADMIN))),
                blockers);
    }


    /**
     * Everything the UI needs to review a draft.
     *
     * @param liveRevision    revision currently live (≠ draft.baseRevision means someone published meanwhile)
     * @param changes         diff live → would-be-published, per section
     * @param conflicts       sections that need a rebase before publishing
     * @param reviewEnvironments affected environments that require an approval
     * @param blockers        human-readable reasons why "publish" is not available
     */
    public record DraftView(FeatureDraft draft, int liveRevision, List<SectionChange> changes, List<String> conflicts,
                            List<String> reviewEnvironments, List<DraftEvent> events, Permissions permissions,
                            List<String> blockers) {

        public record Permissions(boolean edit, boolean requestReview, boolean review, boolean publish, boolean bypass,
                                  boolean discard) {
        }
    }
}
