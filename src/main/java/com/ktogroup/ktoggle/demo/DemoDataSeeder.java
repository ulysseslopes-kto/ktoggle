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
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.RolloutRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.feature.ValueType;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.savedgroup.SavedGroupType;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
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
            connections.create(PRD_CLIENT_KEY, "Demo · Web (produção)", "prd", List.of());
            connections.create(STG_CLIENT_KEY, "Demo · App (staging)", "stg", List.of());
            log.info("Demo data seeded. Client keys: {} (prd), {} (stg)", PRD_CLIENT_KEY, STG_CLIENT_KEY);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void seedCatalog() {
        environments.create("dev", "Desenvolvimento", "Ambiente de desenvolvimento", 0);
        environments.create("stg", "Staging", "Homologação", 1);
        environments.create("prd", "Produção", "Produção — publicação exige aprovação", 2, true);
        projects.create("sportsbook", "Sportsbook", "Apostas esportivas");
        projects.create("casino", "Cassino", "Cassino e cassino ao vivo");
        projects.create("payments", "Pagamentos", "Depósitos, saques e bônus");

        attributes.create(new AttributeCommand("id", AttributeDatatype.STRING, "Identificador do jogador", true, true, List.of(), false));
        attributes.create(new AttributeCommand("country", AttributeDatatype.STRING, "País (ISO 3166)", false, false, List.of(), false));
        attributes.create(new AttributeCommand("platform", AttributeDatatype.ENUM, "Plataforma", false, false,
                List.of("web", "ios", "android"), false));
        attributes.create(new AttributeCommand("appVersion", AttributeDatatype.STRING, "Versão do app", false, false, List.of(), false));
        attributes.create(new AttributeCommand("vipLevel", AttributeDatatype.NUMBER, "Nível VIP (0-5)", false, false, List.of(), false));
        attributes.create(new AttributeCommand("cpf", AttributeDatatype.STRING, "CPF do jogador (dado pessoal)", false, true, List.of(), false));

        savedGroups.create(new SavedGroupCommand("beta-testers", "Beta testers", "Jogadores do programa beta",
                SavedGroupType.LIST, "id", List.of(TextNode.valueOf("user-001"), TextNode.valueOf("user-002"),
                TextNode.valueOf("user-123")), null));
        savedGroups.create(new SavedGroupCommand("mobile-users", "Usuários mobile", "iOS e Android",
                SavedGroupType.CONDITION, null, null, json("{\"platform\":{\"$in\":[\"ios\",\"android\"]}}")));
        savedGroups.create(new SavedGroupCommand("vips", "VIPs", "Nível VIP 3 ou maior",
                SavedGroupType.CONDITION, null, null, json("{\"vipLevel\":{\"$gte\":3}}")));
    }

    private void seedFeatures() {
        feature("new-checkout", "payments", ValueType.BOOLEAN, BooleanNode.FALSE, "Novo fluxo de checkout de depósito",
                List.of("checkout", "deposito"),
                "prd", List.of(
                        force("Beta testers sempre veem", null, List.of("beta-testers"), BooleanNode.TRUE),
                        new RolloutRule(null, "Rollout gradual no BR (app ≥ 2.3)", true,
                                json("{\"country\":\"BR\",\"appVersion\":{\"$vgte\":\"2.3.0\"}}"), List.of(), BooleanNode.TRUE, 0.25, "id")),
                "stg", List.of(force("Todos em staging", null, List.of(), BooleanNode.TRUE)));
        feature("bet-builder", "sportsbook", ValueType.BOOLEAN, BooleanNode.FALSE, "Criar Aposta (bet builder)",
                List.of("sportsbook"),
                "prd", List.of(new RolloutRule(null, "10% dos jogadores", true, null, List.of(), BooleanNode.TRUE, 0.10, "id")),
                "stg", List.of(force("Todos em staging", null, List.of(), BooleanNode.TRUE)));
        feature("casino-lobby-layout", "casino", ValueType.STRING, TextNode.valueOf("grid"), "Layout do lobby do cassino",
                List.of("casino", "ux"),
                "prd", List.of(force("Carrossel no mobile", null, List.of("mobile-users"), TextNode.valueOf("carousel"))),
                "stg", List.of());
        feature("max-bet-limit", "sportsbook", ValueType.NUMBER, IntNode.valueOf(1000), "Limite máximo de aposta (R$)",
                List.of("risco"),
                "prd", List.of(force("Limite VIP", null, List.of("vips"), IntNode.valueOf(50000))),
                "stg", List.of());
        feature("welcome-bonus", "payments", ValueType.JSON, json("{\"enabled\":false}"), "Configuração do bônus de boas-vindas",
                List.of("bonus"),
                "prd", List.of(force("Campanha BR", json("{\"country\":\"BR\"}"), List.of(),
                        json("{\"enabled\":true,\"percent\":100,\"maxAmount\":500,\"rollover\":10}"))),
                "stg", List.of());
        features.create("dark-mode", null, ValueType.BOOLEAN, BooleanNode.FALSE, "Tema escuro no app (ainda em desenvolvimento)",
                "squad-app", List.of("ux"));
    }

    private void feature(String key, String project, ValueType type, JsonNode defaultValue, String description,
                         List<String> tags, String env1, List<Rule> rules1, String env2, List<Rule> rules2) {
        Feature feature = features.create(key, project, type, defaultValue, description, "squad-" + project, tags);
        feature = features.updateEnvironment(key, env1, true, rules1, feature.version());
        features.updateEnvironment(key, env2, true, rules2, feature.version());
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
