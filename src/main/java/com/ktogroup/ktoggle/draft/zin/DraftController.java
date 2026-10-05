package com.ktogroup.ktoggle.draft.zin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.draft.DraftEvent;
import com.ktogroup.ktoggle.draft.DraftService;
import com.ktogroup.ktoggle.draft.DraftService.DraftView;
import com.ktogroup.ktoggle.draft.DraftStatus;
import com.ktogroup.ktoggle.draft.FeatureDraft;
import com.ktogroup.ktoggle.draft.ReviewSettings;
import com.ktogroup.ktoggle.feature.Rule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Draft workflow. Every change to an existing feature goes through a draft; only {@code /publish} changes what
 * SDKs receive. Mutations accept the optional {@code X-Ktoggle-Reason} header (mandatory for emergency publication).
 */
@Tag(name = "Drafts")
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class DraftController {

    private static final int MAX_LIMIT = 200;

    private final DraftService draftService;

    @Operation(summary = "Open drafts of a feature")
    @GetMapping("/features/{key}/drafts")
    public List<FeatureDraft> featureDrafts(@PathVariable String key) {
        return draftService.openDrafts(key);
    }

    @Operation(summary = "Start a draft from the live feature")
    @PostMapping("/features/{key}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public FeatureDraft create(@PathVariable String key, @RequestBody(required = false) TitleRequest request) {
        return draftService.create(key, request == null ? null : request.title());
    }

    @Operation(summary = "Start a draft that reverts the feature to a past revision")
    @PostMapping("/features/{key}/revisions/{revision}/revert")
    @ResponseStatus(HttpStatus.CREATED)
    public FeatureDraft revert(@PathVariable String key, @PathVariable int revision) {
        return draftService.revertTo(key, revision);
    }

    @Operation(summary = "Drafts by status (default: all open drafts) — e.g. the review queue")
    @GetMapping("/drafts")
    public List<FeatureDraft> list(@RequestParam(required = false) Set<DraftStatus> status,
                                   @RequestParam(defaultValue = "100") int limit) {
        return draftService.byStatus(status == null ? EnumSet.noneOf(DraftStatus.class) : EnumSet.copyOf(status),
                Math.clamp(limit, 1, MAX_LIMIT));
    }

    @Operation(summary = "Draft with diff against live, conflicts, review requirements, permissions and history")
    @GetMapping("/drafts/{id}")
    public DraftView get(@PathVariable UUID id) {
        return draftService.view(id);
    }

    @PutMapping("/drafts/{id}/environments/{environmentKey}")
    public FeatureDraft updateEnvironment(@PathVariable UUID id, @PathVariable String environmentKey,
                                          @Valid @RequestBody EnvironmentRequest request) {
        return draftService.updateEnvironment(id, environmentKey, request.enabled(), request.rules(), request.version());
    }

    @PutMapping("/drafts/{id}/metadata")
    public FeatureDraft updateMetadata(@PathVariable UUID id, @Valid @RequestBody MetadataRequest request) {
        return draftService.updateMetadata(id, request.projectKey(), request.defaultValue(), request.description(),
                request.owner(), request.tags(), request.archived(), request.version());
    }

    @PutMapping("/drafts/{id}/title")
    public FeatureDraft rename(@PathVariable UUID id, @Valid @RequestBody TitleRequest request) {
        return draftService.rename(id, request.title(), request.version() == null ? -1 : request.version());
    }

    @Operation(summary = "Absorb what was published meanwhile; conflicting sections keep the draft (keepDraft=true) or live")
    @PostMapping("/drafts/{id}/rebase")
    public FeatureDraft rebase(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean keepDraft,
                               @Valid @RequestBody VersionRequest request) {
        return draftService.rebase(id, keepDraft, request.version());
    }

    @PostMapping("/drafts/{id}/request-review")
    public FeatureDraft requestReview(@PathVariable UUID id, @RequestBody(required = false) CommentRequest request) {
        return draftService.requestReview(id, request == null ? null : request.comment());
    }

    @PostMapping("/drafts/{id}/approve")
    public FeatureDraft approve(@PathVariable UUID id, @RequestBody(required = false) CommentRequest request) {
        return draftService.approve(id, request == null ? null : request.comment());
    }

    @PostMapping("/drafts/{id}/request-changes")
    public FeatureDraft requestChanges(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return draftService.requestChanges(id, request.comment());
    }

    @PostMapping("/drafts/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftEvent comment(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return draftService.comment(id, request.comment());
    }

    @Operation(summary = "Publish the draft; bypass=true is an admin emergency publication without approval (reason required)")
    @PostMapping("/drafts/{id}/publish")
    public FeatureDraft publish(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean bypass) {
        return draftService.publish(id, bypass);
    }

    @PostMapping("/drafts/{id}/discard")
    public FeatureDraft discard(@PathVariable UUID id) {
        return draftService.discard(id);
    }

    @GetMapping("/settings/review")
    public ReviewSettings reviewSettings() {
        return draftService.reviewSettings();
    }

    @PutMapping("/settings/review")
    public ReviewSettings updateReviewSettings(@Valid @RequestBody ReviewSettingsRequest request) {
        return draftService.updateReviewSettings(new ReviewSettings(request.approverRoles(), request.approverUsers(),
                request.allowSelfApproval(), request.resetReviewOnChange(), request.bypassEnabled(), null, null, null),
                request.version());
    }

    public record TitleRequest(String title, Long version) {
    }

    public record EnvironmentRequest(boolean enabled, List<Rule> rules, @NotNull Long version) {
    }

    public record MetadataRequest(String projectKey, @NotNull JsonNode defaultValue, String description, String owner,
                                  List<String> tags, boolean archived, @NotNull Long version) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CommentRequest(String comment) {
    }

    public record ReviewSettingsRequest(List<String> approverRoles, List<String> approverUsers, boolean allowSelfApproval,
                                        boolean resetReviewOnChange, boolean bypassEnabled, @NotNull Long version) {
    }
}
