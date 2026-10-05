package com.ktogroup.ktoggle.feature.zin;

import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort.FeatureFilter;
import com.ktogroup.ktoggle.feature.FeatureRevision;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.zdto.FeatureRequests.CreateFeatureRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Features are created directly (they start disabled in every environment). Every later change — toggles, rules,
 * default value, metadata, archiving, reverts — goes through a draft ({@code /admin/v1/features/{key}/drafts}).
 */
@Tag(name = "Features")
@RestController
@RequestMapping("/admin/v1/features")
@RequiredArgsConstructor
public class FeatureController {

    private final FeatureService featureService;

    @GetMapping
    public List<Feature> list(@RequestParam(required = false) String projectKey,
                              @RequestParam(required = false) String tag,
                              @Parameter(description = "Matches key or description") @RequestParam(required = false) String search,
                              @RequestParam(required = false, defaultValue = "false") Boolean archived) {
        return featureService.findAll(new FeatureFilter(projectKey, tag, search, archived));
    }

    @GetMapping("/{key}")
    public Feature get(@PathVariable String key) {
        return featureService.get(key);
    }

    @Operation(summary = "Create a feature (disabled in every environment)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Feature create(@Valid @RequestBody CreateFeatureRequest request) {
        return featureService.create(request.key(), request.projectKey(), request.valueType(), request.defaultValue(),
                request.description(), request.owner(), request.tags());
    }

    @Operation(summary = "Features that depend on this one through prerequisites")
    @GetMapping("/{key}/dependents")
    public List<FeatureService.Dependent> dependents(@PathVariable String key) {
        return featureService.dependents(key);
    }

    @GetMapping("/{key}/revisions")
    public List<FeatureRevision> revisions(@PathVariable String key) {
        return featureService.revisions(key);
    }

    @GetMapping("/{key}/revisions/{revision}")
    public FeatureRevision revision(@PathVariable String key, @PathVariable int revision) {
        return featureService.revision(key, revision);
    }
}
