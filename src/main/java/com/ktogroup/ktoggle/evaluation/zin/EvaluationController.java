package com.ktogroup.ktoggle.evaluation.zin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.evaluation.EvaluationResult;
import com.ktogroup.ktoggle.evaluation.EvaluationService;
import com.ktogroup.ktoggle.evaluation.EvaluationService.ReplayResult;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Evaluation")
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @Operation(summary = "Evaluate a feature for some attributes against the current (or a proposed) configuration")
    @PostMapping("/simulate")
    public EvaluationResult simulate(@Valid @RequestBody SimulateRequest request) {
        return evaluationService.simulate(request.featureKey(), request.environmentKey(), request.attributes(), request.proposed(),
                request.at());
    }

    @Operation(summary = "Reproduce a past decision from an immutable bundle")
    @PostMapping("/replay")
    public ReplayResult replay(@Valid @RequestBody ReplayRequest request) {
        return evaluationService.replay(request.bundleHash(), request.featureKey(), request.attributes());
    }

    @Operation(summary = "Reproduce a past decision from the bundle a client key was serving at an instant")
    @PostMapping("/replay/at")
    public ReplayResult replayAt(@Valid @RequestBody ReplayAtRequest request) {
        return evaluationService.replayAt(request.clientKey(), request.instant(), request.featureKey(), request.attributes());
    }

    /** @param proposed optional unsaved environment settings to test before saving them */
    public record SimulateRequest(@NotBlank String featureKey, @NotBlank String environmentKey, JsonNode attributes,
                                  EnvironmentSettings proposed, Instant at) {
    }

    public record ReplayRequest(@NotBlank String bundleHash, @NotBlank String featureKey, JsonNode attributes) {
    }

    public record ReplayAtRequest(@NotBlank String clientKey, @NotNull Instant instant, @NotBlank String featureKey,
                                  JsonNode attributes) {
    }
}
