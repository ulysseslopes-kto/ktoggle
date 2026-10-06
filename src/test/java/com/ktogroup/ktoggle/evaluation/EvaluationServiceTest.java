package com.ktogroup.ktoggle.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleBody;
import com.ktogroup.ktoggle.bundle.BundleService;
import com.ktogroup.ktoggle.bundle.BundleService.VerifiedBundle;
import com.ktogroup.ktoggle.bundle.PayloadCompiler;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.decision.AttributeDigester;
import com.ktogroup.ktoggle.decision.DecisionProperties;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.evaluation.EvaluationService.ReplayResult;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.RuleValidator;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.ztest.TestBundles;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EvaluationServiceTest {

    private final TestBundles testBundles = new TestBundles();
    private final ObjectMapper mapper = testBundles.objectMapper();
    private final BundleService bundleService = mock(BundleService.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);
    private final AttributeDigester digester = new AttributeDigester(testBundles.canonicalJson(),
            new DecisionProperties("k1", java.util.Base64.getEncoder().encodeToString(new byte[32]), 0, 0, null, null, 0, 0));
    private final EvaluationService service = new EvaluationService(mock(FeatureService.class), environmentService,
            mock(SavedGroupService.class), mock(AttributeService.class), mock(RuleValidator.class), new PayloadCompiler(),
            bundleService, new GrowthBookEvaluator(mapper), digester, java.time.Clock.systemUTC());

    @Test
    void replay_evaluates_the_bundle_payload_and_reports_where_the_decision_came_from() {
        Bundle bundle = testBundles.bundle("sdk-a", true);
        when(bundleService.get(bundle.hash())).thenReturn(new VerifiedBundle(bundle, testBundles.body("sdk-a", true)));
        JsonNode attributes = JsonNodeFactory.instance.objectNode().put("country", "BR");

        ReplayResult result = service.replay(bundle.hash(), "checkout", attributes);

        assertThat(result.result().value().asBoolean()).isTrue();
        assertThat(result.bundleHash()).isEqualTo(bundle.hash());
        assertThat(result.clientKey()).isEqualTo("sdk-a");
        assertThat(result.featureRevision()).isEqualTo(1);
        assertThat(result.activation()).isNull();
        assertThat(result.attributesDigest()).isEqualTo(digester.digest(attributes));
        assertThat(result.digestKeyId()).isEqualTo("k1");
    }

    @Test
    void replay_of_a_feature_the_bundle_does_not_serve_has_no_revision() {
        Bundle bundle = testBundles.bundle("sdk-a", true);
        when(bundleService.get(bundle.hash())).thenReturn(new VerifiedBundle(bundle, testBundles.body("sdk-a", true)));

        ReplayResult result = service.replay(bundle.hash(), "other", null);

        assertThat(result.featureRevision()).isNull();
        assertThat(result.result().source()).isEqualTo("unknownFeature");
    }

    @Test
    void replay_refuses_bundles_produced_for_an_evaluator_this_build_cannot_reproduce() {
        Bundle bundle = testBundles.bundle("sdk-a", true);
        BundleBody body = testBundles.body("sdk-a", true);
        BundleBody foreign = new BundleBody(body.contractVersion(), body.target(),
                new BundleBody.Evaluator("growthbook-features", 2, "growthbook-sdk-java@9.9.9"), body.sources(), body.payload());
        when(bundleService.get(bundle.hash())).thenReturn(new VerifiedBundle(bundle, foreign));

        assertThatThrownBy(() -> service.replay(bundle.hash(), "checkout", null))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(MessageCode.UNSUPPORTED_EVALUATOR));
    }

    @Test
    void replay_at_an_instant_uses_the_bundle_of_the_activation_in_force_and_returns_it() {
        Bundle bundle = testBundles.bundle("sdk-a", true);
        BundleActivation activation = TestBundles.activation("sdk-a", 3, bundle.hash(), "p");
        Instant instant = Instant.parse("2026-10-05T12:30:00Z");
        when(bundleService.activationAt("sdk-a", instant)).thenReturn(activation);
        when(bundleService.get(bundle.hash())).thenReturn(new VerifiedBundle(bundle, testBundles.body("sdk-a", true)));

        ReplayResult result = service.replayAt("sdk-a", instant, "checkout", null);

        assertThat(result.activation()).isEqualTo(activation);
        assertThat(result.bundleHash()).isEqualTo(bundle.hash());
        assertThat(result.result().value().asBoolean()).isTrue();
    }

    @Test
    void replay_at_an_instant_without_activation_is_not_found() {
        Instant instant = Instant.parse("2000-01-01T00:00:00Z");
        when(bundleService.activationAt("sdk-a", instant)).thenThrow(new NotFoundException(MessageCode.ENTITY_NOT_FOUND, "none"));

        assertThatThrownBy(() -> service.replayAt("sdk-a", instant, "checkout", null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void simulation_of_an_unknown_environment_fails_before_touching_the_feature() {
        org.mockito.Mockito.doThrow(new NotFoundException("Environment", "nope")).when(environmentService).requireExists("nope");

        assertThatThrownBy(() -> service.simulate("checkout", "nope", null, null, null, null)).isInstanceOf(NotFoundException.class);
    }
}
