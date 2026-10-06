package com.ktogroup.ktoggle.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleService;
import com.ktogroup.ktoggle.bundle.BundleService.VerifiedBundle;
import com.ktogroup.ktoggle.bundle.PayloadCompiler;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.decision.AttributeDigester;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.RuleValidator;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <ul>
 *   <li><b>simulate</b>: "what would this user get?" against the current configuration — or a proposed
 *   feature content (a draft) or environment setting not saved yet — compiled exactly as it would be published.</li>
 *   <li><b>replay</b>: "what did this user get?" against an immutable, verified bundle, independent of anything
 *   that changed since.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final FeatureService featureService;
    private final EnvironmentService environmentService;
    private final SavedGroupService savedGroupService;
    private final AttributeService attributeService;
    private final RuleValidator ruleValidator;
    private final PayloadCompiler compiler;
    private final BundleService bundleService;
    private final GrowthBookEvaluator evaluator;
    private final AttributeDigester digester;
    private final Clock clock;

    @Transactional(readOnly = true)
    public EvaluationResult simulate(String featureKey, String environmentKey, JsonNode attributes,
                                     EnvironmentSettings proposed, FeatureSnapshot proposedFeature, Instant at) {
        environmentService.requireExists(environmentKey);
        Feature feature = featureService.get(featureKey);
        if (proposedFeature != null) {
            FeatureSnapshot draft = featureService.validate(feature.valueType(), proposedFeature.withKey(feature.key()));
            feature = feature.withDefaultValue(draft.defaultValue()).withPrerequisites(draft.prerequisites())
                    .withEnvironments(draft.environments());
        }
        if (proposed != null) {
            Map<String, EnvironmentSettings> environments = new HashMap<>(feature.environments());
            environments.put(environmentKey, new EnvironmentSettings(proposed.enabled(), ruleValidator.validate(
                    feature.valueType(), proposed.rules(), attributeService.findAllByKey(),
                    savedGroupService.findAllByKey().keySet())));
            feature = feature.withEnvironments(environments);
        }
        SdkConnection virtual = new SdkConnection("simulation", "simulation", environmentKey, List.of(), null, null, null, null);
        // every other feature too: prerequisites evaluate their parents from the same payload
        String key = feature.key();
        List<Feature> features = new ArrayList<>(featureService.findAllActive().stream().filter(f -> !f.key().equals(key)).toList());
        features.add(feature);
        var compiled = compiler.compile(virtual, features, savedGroupService.findAllByKey(),
                at == null ? Ids.now(clock) : at);
        return evaluator.evaluate(compiled.payload(), featureKey, attributes);
    }

    @Transactional(readOnly = true)
    public ReplayResult replay(String bundleHash, String featureKey, JsonNode attributes) {
        VerifiedBundle bundle = bundleService.get(bundleHash);
        evaluator.requireSupported(bundle.body().evaluator());
        EvaluationResult result = evaluator.evaluate(bundle.body().payload(), featureKey, attributes);
        return new ReplayResult(bundleHash, bundle.body().target().clientKey(), bundle.body().evaluator().referenceEvaluator(),
                bundle.body().sources().get(featureKey), null, digester.digest(attributes), digester.keyId(), result);
    }

    @Transactional(readOnly = true)
    public ReplayResult replayAt(String clientKey, Instant instant, String featureKey, JsonNode attributes) {
        BundleActivation activation = bundleService.activationAt(clientKey, instant);
        ReplayResult result = replay(activation.bundleHash(), featureKey, attributes);
        return new ReplayResult(result.bundleHash(), result.clientKey(), result.bundleEvaluator(), result.featureRevision(),
                activation, result.attributesDigest(), result.digestKeyId(), result.result());
    }

    /**
     * @param bundleEvaluator  evaluator the bundle was published for
     * @param featureRevision  revision of the feature compiled into the bundle (null if not served by it)
     * @param activation       when replaying "at an instant", the activation that was in force
     * @param attributesDigest HMAC of the attributes, comparable with the digest of reported decision events
     */
    public record ReplayResult(String bundleHash, String clientKey, String bundleEvaluator, Integer featureRevision,
                               BundleActivation activation, String attributesDigest, String digestKeyId,
                               EvaluationResult result) {
    }
}
