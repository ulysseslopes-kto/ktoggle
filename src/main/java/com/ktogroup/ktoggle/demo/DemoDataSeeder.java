package com.ktogroup.ktoggle.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ktogroup.ktoggle.attribute.AttributeDatatype;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.attribute.AttributeService.AttributeCommand;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.ExperimentRule;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.feature.RuleSchedule;
import com.ktogroup.ktoggle.feature.ValueType;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Demo data for presentations ({@code ktoggle.demo.seed=true}, profile {@code demo}). Runs once, on an empty
 * database, through the regular services — so the seeded data is audited, versioned and published as signed bundles
 * exactly like real changes. Everything here is fictitious.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ktoggle.demo.seed", havingValue = "true")
public class DemoDataSeeder {

    static final String ACTOR = "demo-seed";
    public static final String PRD_CLIENT_KEY = "sdk-demoprd00001";
    public static final String STG_CLIENT_KEY = "sdk-demostg00001";

    private final EnvironmentService environments;
    private final ProjectService projects;
    private final AttributeService attributes;
    private final SavedGroupService savedGroups;
    private final FeatureService features;
    private final SdkConnectionService connections;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!environments.findAll().isEmpty()) {
            log.info("Demo seed skipped: database already has data");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR, null, List.of()));
        try {
            seedCatalog();
            seedFeatures();
            connections.create(PRD_CLIENT_KEY, "Demo · Web (production)", "prd", List.of());
            connections.create(STG_CLIENT_KEY, "Demo · App (staging)", "stg", List.of());
            log.info("Demo data seeded. Client keys: {} (prd), {} (stg)", PRD_CLIENT_KEY, STG_CLIENT_KEY);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void seedCatalog() {
        environments.create("dev", "Development", "Development environment", 0);
        environments.create("stg", "Staging", "Pre-production", 1);
        environments.create("prd", "Production", "Production — publishing requires approval", 2, true);
        projects.create("sportsbook", "Sportsbook", "Sports betting");
        projects.create("casino", "Casino", "Casino and live casino");
        projects.create("payments", "Payments", "Deposits, withdrawals and bonuses");

        attributes.create(new AttributeCommand("id", AttributeDatatype.STRING, "Player identifier", true, true, List.of(), false));
        attributes.create(new AttributeCommand("country", AttributeDatatype.STRING, "Country (ISO 3166)", false, false, List.of(), false));
        attributes.create(new AttributeCommand("platform", AttributeDatatype.ENUM, "Platform", false, false,
                List.of("web", "ios", "android"), false));
        attributes.create(new AttributeCommand("appVersion", AttributeDatatype.STRING, "App version", false, false, List.of(), false));
        attributes.create(new AttributeCommand("vipLevel", AttributeDatatype.NUMBER, "VIP level (0-5)", false, false, List.of(), false));
        attributes.create(new AttributeCommand("cpf", AttributeDatatype.STRING, "Player CPF (personal data)", false, true, List.of(), false));

        savedGroups.create(new SavedGroupCommand("beta-testers", "Beta testers", "Players in the beta program",
                SavedGroupType.LIST, "id", List.of(TextNode.valueOf("user-001"), TextNode.valueOf("user-002"),
                TextNode.valueOf("user-123")), null));
        savedGroups.create(new SavedGroupCommand("mobile-users", "Mobile users", "iOS and Android",
                SavedGroupType.CONDITION, null, null, json("{\"platform\":{\"$in\":[\"ios\",\"android\"]}}")));
        savedGroups.create(new SavedGroupCommand("vips", "VIPs", "VIP level 3 or higher",
                SavedGroupType.CONDITION, null, null, json("{\"vipLevel\":{\"$gte\":3}}")));
    }

    private void seedFeatures() {
        feature("new-checkout", "payments", ValueType.BOOLEAN, BooleanNode.FALSE, "New deposit checkout flow",
                List.of("checkout", "deposit"),
                "prd", List.of(
                        force("Beta testers always see it", null, List.of("beta-testers"), BooleanNode.TRUE),
                        new RolloutRule(null, "Gradual rollout in BR (app ≥ 2.3)", true,
                                json("{\"country\":\"BR\",\"appVersion\":{\"$vgte\":\"2.3.0\"}}"), List.of(), BooleanNode.TRUE, 0.25, "id")),
                "stg", List.of(force("Everyone in staging", null, List.of(), BooleanNode.TRUE)));
        feature("bet-builder", "sportsbook", ValueType.BOOLEAN, BooleanNode.FALSE, "Bet builder",
                List.of("sportsbook"),
                "prd", List.of(new RolloutRule(null, "10% of players", true, null, List.of(), BooleanNode.TRUE, 0.10, "id")),
                "stg", List.of(force("Everyone in staging", null, List.of(), BooleanNode.TRUE)));
        feature("casino-lobby-layout", "casino", ValueType.STRING, TextNode.valueOf("grid"), "Casino lobby layout",
                List.of("casino", "ux"),
                "prd", List.of(force("Carousel on mobile", null, List.of("mobile-users"), TextNode.valueOf("carousel"))),
                "stg", List.of());
        feature("max-bet-limit", "sportsbook", ValueType.NUMBER, IntNode.valueOf(1000), "Maximum bet limit (BRL)",
                List.of("risk"),
                "prd", List.of(force("VIP limit", null, List.of("vips"), IntNode.valueOf(50000))),
                "stg", List.of());
        feature("welcome-bonus", "payments", ValueType.JSON, json("{\"enabled\":false}"), "Welcome bonus configuration",
                List.of("bonus"),
                "prd", List.of(
                        new ForceRule(null, "Black Friday boost", true, json("{\"country\":\"BR\"}"), List.of(),
                                json("{\"enabled\":true,\"percent\":200,\"maxAmount\":1000,\"rollover\":10}"),
                                new RuleSchedule(Instant.parse("2026-11-27T03:00:00Z"), Instant.parse("2026-12-01T03:00:00Z"))),
                        force("BR campaign", json("{\"country\":\"BR\"}"), List.of(),
                                json("{\"enabled\":true,\"percent\":100,\"maxAmount\":500,\"rollover\":10}"))),
                "stg", List.of());
        feature("deposit-button-copy", "payments", ValueType.STRING, TextNode.valueOf("Deposit"), "Deposit button copy (A/B test)",
                List.of("experiment", "conversion"),
                "prd", List.of(depositCopyExperiment(0.5)),
                "stg", List.of(depositCopyExperiment(1.0)));
        features.create("dark-mode", null, ValueType.BOOLEAN, BooleanNode.FALSE, "Dark theme in the app (still in development)",
                "squad-app", List.of("ux"));
    }

    private void feature(String key, String project, ValueType type, JsonNode defaultValue, String description,
                         List<String> tags, String env1, List<Rule> rules1, String env2, List<Rule> rules2) {
        Feature feature = features.create(key, project, type, defaultValue, description, "squad-" + project, tags);
        feature = features.updateEnvironment(key, env1, true, rules1, feature.version());
        features.updateEnvironment(key, env2, true, rules2, feature.version());
    }

    private static ExperimentRule depositCopyExperiment(double coverage) {
        return new ExperimentRule(null, "Deposit button copy", true, null, List.of(), "deposit-button-copy", "id", coverage,
                List.of(new ExperimentRule.Variation("0", "Control", TextNode.valueOf("Deposit"), 0.34),
                        new ExperimentRule.Variation("1", "Add funds", TextNode.valueOf("Add funds"), 0.33),
                        new ExperimentRule.Variation("2", "Play now", TextNode.valueOf("Deposit & play"), 0.33)),
                ExperimentRule.DEFAULT_HASH_VERSION, null);
    }

    private static ForceRule force(String description, JsonNode condition, List<String> groups, JsonNode value) {
        return new ForceRule(null, description, true, condition, groups, value);
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
