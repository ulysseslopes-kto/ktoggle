package com.ktogroup.ktoggle.growthbook.zin;

import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.growthbook.GrowthBookImporter;
import com.ktogroup.ktoggle.growthbook.GrowthBookProperties;
import com.ktogroup.ktoggle.growthbook.ImportReport;
import com.ktogroup.ktoggle.growthbook.ShadowRun;
import com.ktogroup.ktoggle.growthbook.ShadowService;
import com.ktogroup.ktoggle.growthbook.ShadowService.ConnectionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Migration from GrowthBook: import and shadow comparison. Import and on-demand runs are admin-only. */
@Tag(name = "GrowthBook migration")
@RestController
@RequiredArgsConstructor
public class GrowthBookController {

    private final GrowthBookProperties properties;
    private final GrowthBookImporter importer;
    private final ShadowService shadow;
    private final ChangeContextProvider changeContextProvider;

    /** What is configured (never the key itself). */
    public record MigrationStatus(boolean configured, String apiHost, String sdkHost, boolean canImport, boolean shadowEnabled,
                                  Duration shadowInterval, int samples, int readyAfter) {
    }

    @GetMapping("/admin/v1/growthbook/status")
    public MigrationStatus status() {
        return new MigrationStatus(properties.configured(), properties.apiHost(), properties.sdkHost(),
                properties.configured() && properties.secretKey() != null, properties.shadow().enabled(),
                properties.shadow().interval(), properties.shadow().samples(), properties.shadow().readyAfter());
    }

    /**
     * @param dryRun             true (default): report what would happen without writing anything
     * @param environmentMapping GrowthBook environment id &rarr; ktoggle environment key (e.g. production &rarr; prd)
     */
    public record ImportRequest(Boolean dryRun, Map<String, String> environmentMapping) {
    }

    @Operation(summary = "Import projects, environments, attributes, saved groups, features and SDK connections (dry run by default)")
    @PostMapping("/admin/v1/growthbook/import")
    public ImportReport importFromGrowthBook(@RequestBody(required = false) ImportRequest request) {
        boolean dryRun = request == null || request.dryRun() == null || request.dryRun();
        return importer.run(dryRun, request == null ? null : request.environmentMapping());
    }

    @Operation(summary = "Shadow comparison status per client key, with readiness to migrate")
    @GetMapping("/admin/v1/shadow")
    public List<ConnectionStatus> shadowStatus() {
        return shadow.status();
    }

    @Operation(summary = "Compare all client keys (or one) with GrowthBook now")
    @PostMapping("/admin/v1/shadow/run")
    public List<ShadowRun> runNow(@RequestParam(required = false) String clientKey) {
        return shadow.runNow(clientKey, changeContextProvider.current().actor());
    }

    @GetMapping("/admin/v1/shadow/{clientKey}/runs")
    public List<ShadowRun> runs(@PathVariable String clientKey, @RequestParam(defaultValue = "20") int limit) {
        return shadow.runs(clientKey, limit);
    }
}
