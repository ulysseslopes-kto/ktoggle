package com.ktogroup.ktoggle.growthbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.attribute.AttributeService.AttributeCommand;
import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.commons.Keys;
import com.ktogroup.ktoggle.environment.Environment;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeatureService;
import com.ktogroup.ktoggle.feature.FeatureSnapshot;
import com.ktogroup.ktoggle.feature.PrerequisiteValidator;
import com.ktogroup.ktoggle.growthbook.GrowthBookMapper.FeaturePlan;
import com.ktogroup.ktoggle.growthbook.ImportReport.Action;
import com.ktogroup.ktoggle.project.Project;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService.SavedGroupCommand;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Imports an existing GrowthBook (read through its REST API) into ktoggle: projects, environments, attributes, saved
 * groups, features and SDK connections, in that order. Client keys are preserved, so consumers later migrate by changing
 * only the host. Idempotent: running it again updates what changed in GrowthBook and leaves the rest untouched.
 *
 * <p>Each item is applied on its own: one failure is reported and does not stop the others. Features are published
 * directly (audited as {@code IMPORT}) rather than through drafts: an import is an admin operation that mirrors what is
 * already live in GrowthBook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthBookImporter {

    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9_.:|-]{1,150}$");

    private final GrowthBookClient client;
    private final GrowthBookMapper mapper;
    private final ProjectService projects;
    private final EnvironmentService environments;
    private final AttributeService attributes;
    private final SavedGroupService savedGroups;
    private final FeatureService features;
    private final SdkConnectionService connections;
    private final ObjectMapper objectMapper;

    /**
     * @param environmentMapping GrowthBook environment id &rarr; ktoggle environment key; unmapped environments are
     *                           imported under their GrowthBook id
     */
    public ImportReport run(boolean dryRun, Map<String, String> environmentMapping) {
        ImportReport.Builder report = ImportReport.builder(dryRun);
        Map<String, String> projectKeys = importProjects(dryRun, report);
        Map<String, String> environmentKeys = importEnvironments(dryRun, environmentMapping == null ? Map.of() : environmentMapping, report);
        importAttributes(dryRun, report);
        importSavedGroups(dryRun, report);
        importFeatures(dryRun, projectKeys, environmentKeys, report);
        importConnections(dryRun, projectKeys, environmentKeys, report);
        ImportReport result = report.build();
        log.info("GrowthBook import ({}): {}", dryRun ? "dry run" : "applied", result.totals());
        return result;
    }

    private Map<String, String> importProjects(boolean dryRun, ImportReport.Builder report) {
        Map<String, Project> existing = new HashMap<>();
        projects.findAll().forEach(p -> existing.put(p.key(), p));
        Map<String, String> keys = new HashMap<>();
        for (JsonNode gb : client.list("projects")) {
            String publicId = gb.path("publicId").asText("");
            String key = KEY.matcher(publicId).matches() ? publicId : gb.path("id").asText();
            keys.put(gb.path("id").asText(), key);
            apply(report, "project", key, existing.containsKey(key) ? Action.UNCHANGED : Action.CREATE, List.of(), dryRun,
                    () -> projects.create(key, gb.path("name").asText(key), gb.path("description").asText(null)));
        }
        return keys;
    }

    private Map<String, String> importEnvironments(boolean dryRun, Map<String, String> mapping, ImportReport.Builder report) {
        Set<String> existing = new HashSet<>(environments.findAll().stream().map(Environment::key).toList());
        Map<String, String> keys = new LinkedHashMap<>();
        int order = existing.size();
        for (JsonNode gb : client.list("environments")) {
            String id = gb.path("id").asText();
            String key = mapping.getOrDefault(id, id);
            keys.put(id, key);
            int sortOrder = order++;
            List<String> notes = key.equals(id) ? List.of() : List.of("GrowthBook environment '" + id + "' imported into '" + key + "'");
            apply(report, "environment", key, existing.contains(key) ? Action.UNCHANGED : Action.CREATE, notes, dryRun,
                    () -> environments.create(key, key, gb.path("description").asText(null), sortOrder));
        }
        return keys;
    }

    private void importAttributes(boolean dryRun, ImportReport.Builder report) {
        Map<String, Attribute> existing = attributes.findAllByKey();
        for (JsonNode gb : client.list("attributes")) {
            List<String> warnings = new ArrayList<>();
            Optional<AttributeCommand> command = mapper.attribute(gb, warnings);
            String key = gb.path("property").asText();
            if (command.isEmpty()) {
                report.add("attribute", key, Action.UNSUPPORTED, warnings);
                continue;
            }
            Attribute current = existing.get(key);
            if (current != null && current.datatype() != command.get().datatype()) {
                report.add("attribute", key, Action.FAILED, "exists in ktoggle as " + current.datatype() + ", GrowthBook says "
                        + command.get().datatype());
                continue;
            }
            apply(report, "attribute", key, current == null ? Action.CREATE : Action.UNCHANGED, warnings, dryRun,
                    () -> attributes.create(command.get()));
        }
    }

    private void importSavedGroups(boolean dryRun, ImportReport.Builder report) {
        Map<String, SavedGroup> existing = savedGroups.findAllByKey();
        for (JsonNode gb : client.list("saved-groups")) {
            SavedGroupCommand command = mapper.savedGroup(gb);
            SavedGroup current = existing.get(command.key());
            if (current == null) {
                apply(report, "savedGroup", command.key(), Action.CREATE, List.of(command.name()), dryRun, () -> savedGroups.create(command));
            } else if (sameGroup(current, command)) {
                report.add("savedGroup", command.key(), Action.UNCHANGED, command.name());
            } else {
                apply(report, "savedGroup", command.key(), Action.UPDATE, List.of(command.name()), dryRun,
                        () -> savedGroups.update(command.key(), command, current.version()));
            }
        }
    }

    private void importFeatures(boolean dryRun, Map<String, String> projectKeys, Map<String, String> environmentKeys,
                                ImportReport.Builder report) {
        Map<String, JsonNode> experimentCache = new HashMap<>();
        Function<String, Optional<JsonNode>> experiments = id -> Optional.ofNullable(
                experimentCache.computeIfAbsent(id, i -> client.experiment(i).orElse(null)));
        Map<String, FeatureSnapshot> planned = new LinkedHashMap<>();
        Map<String, List<String>> warnings = new HashMap<>();
        for (JsonNode gb : client.list("features")) {
            String key = gb.path("id").asText();
            try {
                Keys.requireValid("Feature", key);
                FeaturePlan plan = mapper.feature(gb, environmentKeys, experiments);
                String projectKey = plan.gbProjectId() == null ? null : projectKeys.getOrDefault(plan.gbProjectId(), plan.gbProjectId());
                planned.put(key, new FeatureSnapshot(key, projectKey, plan.valueType(), plan.defaultValue(), plan.description(),
                        plan.owner(), plan.tags(), plan.archived(), plan.prerequisites(), plan.environments()));
                warnings.put(key, plan.warnings());
            } catch (RuntimeException e) {
                report.add("feature", key, Action.UNSUPPORTED, e.getMessage());
            }
        }
        Map<String, Feature> existing = new HashMap<>();
        features.findAll(new com.ktogroup.ktoggle.feature.FeaturePersistencePort.FeatureFilter(null, null, null, null))
                .forEach(f -> existing.put(f.key(), f));
        for (String key : dependencyOrder(planned)) {
            FeatureSnapshot snapshot = planned.get(key);
            Feature current = existing.get(key);
            List<String> notes = warnings.getOrDefault(key, List.of());
            if (current != null && current.valueType() != snapshot.valueType()) {
                report.add("feature", key, Action.FAILED, "exists in ktoggle as " + current.valueType());
                continue;
            }
            if (current != null && same(current.snapshot(), snapshot)) {
                report.add("feature", key, Action.UNCHANGED, notes);
                continue;
            }
            apply(report, "feature", key, current == null ? Action.CREATE : Action.UPDATE, notes, dryRun, () -> {
                if (current == null) {
                    features.create(key, snapshot.projectKey(), snapshot.valueType(), snapshot.defaultValue(), snapshot.description(),
                            snapshot.owner(), snapshot.tags());
                }
                features.publish(key, snapshot, AuditAction.IMPORT);
            });
        }
    }

    private void importConnections(boolean dryRun, Map<String, String> projectKeys, Map<String, String> environmentKeys,
                                   ImportReport.Builder report) {
        for (JsonNode gb : client.list("sdk-connections")) {
            String clientKey = gb.path("key").asText();
            Optional<SdkConnection> current = connections.find(clientKey);
            if (current.isPresent()) {
                report.add("sdkConnection", clientKey, Action.UNCHANGED, gb.path("name").asText());
                continue;
            }
            String environment = environmentKeys.get(gb.path("environment").asText());
            if (environment == null) {
                report.add("sdkConnection", clientKey, Action.UNSUPPORTED, "environment '" + gb.path("environment").asText()
                        + "' was not imported");
                continue;
            }
            List<String> projectList = new ArrayList<>();
            gb.path("projects").forEach(p -> projectList.add(projectKeys.getOrDefault(p.asText(), p.asText())));
            List<String> notes = new ArrayList<>(List.of(gb.path("name").asText()));
            boolean encrypt = gb.path("encryptPayload").asBoolean(false);
            boolean remote = gb.path("remoteEvalEnabled").asBoolean(false);
            if (encrypt) {
                notes.add("payload encryption kept with GrowthBook's key");
            }
            if (remote) {
                notes.add("remote evaluation enabled");
            }
            apply(report, "sdkConnection", clientKey, Action.CREATE, notes, dryRun, () -> {
                SdkConnection created = connections.create(clientKey, gb.path("name").asText(clientKey), environment, projectList);
                if (encrypt) {
                    connections.useDecryptionKey(clientKey, gb.path("encryptionKey").asText());
                } else if (remote) {
                    connections.update(clientKey, created.name(), created.projectKeys(), null, true, created.version());
                }
            });
        }
    }

    /** Parents before children (feature and rule prerequisites), so each publish validates against existing parents. */
    private static List<String> dependencyOrder(Map<String, FeatureSnapshot> planned) {
        List<String> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String key : planned.keySet()) {
            visit(key, planned, visiting, done, order);
        }
        return order;
    }

    private static void visit(String key, Map<String, FeatureSnapshot> planned, Set<String> visiting, Set<String> done,
                              List<String> order) {
        if (done.contains(key) || !visiting.add(key)) {
            return;
        }
        FeatureSnapshot snapshot = planned.get(key);
        if (snapshot != null) {
            PrerequisiteValidator.parents(snapshot).forEach(parent -> visit(parent, planned, visiting, done, order));
            order.add(key);
        }
        done.add(key);
    }

    private boolean same(FeatureSnapshot a, FeatureSnapshot b) {
        return Objects.equals(objectMapper.valueToTree(a), objectMapper.valueToTree(b));
    }

    private boolean sameGroup(SavedGroup current, SavedGroupCommand command) {
        return Objects.equals(current.name(), command.name()) && current.type() == command.type()
                && Objects.equals(current.attributeKey(), command.attributeKey())
                && Objects.equals(objectMapper.valueToTree(orEmpty(current.values())), objectMapper.valueToTree(orEmpty(command.values())))
                && Objects.equals(current.condition(), command.condition());
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** Records the planned action and, unless dry run, performs it; failures become FAILED items with the reason. */
    private static void apply(ImportReport.Builder report, String type, String key, Action action, List<String> notes,
                              boolean dryRun, Runnable change) {
        if (dryRun || action == Action.UNCHANGED) {
            report.add(type, key, action, notes);
            return;
        }
        try {
            change.run();
            report.add(type, key, action, notes);
        } catch (RuntimeException e) {
            List<String> messages = new ArrayList<>(notes);
            messages.add(e.getMessage());
            report.add(type, key, Action.FAILED, messages);
        }
    }
}
