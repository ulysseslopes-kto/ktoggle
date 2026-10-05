package com.ktogroup.ktoggle.decision.zin;

import com.ktogroup.ktoggle.decision.DecisionIngestService;
import com.ktogroup.ktoggle.decision.DecisionIngestService.DecisionReport;
import com.ktogroup.ktoggle.decision.DecisionIngestService.IngestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "SDK (GrowthBook compatible)")
@RestController
@RequiredArgsConstructor
public class DecisionIngestController {

    private final DecisionIngestService ingestService;

    @Operation(summary = "Opt-in: report decisions from the SDK feature-usage callback (batched, asynchronous)")
    @PostMapping("/api/decisions/{clientKey}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestResult ingest(@PathVariable String clientKey, @RequestBody DecisionBatch batch) {
        return ingestService.ingest(clientKey, batch.events());
    }

    public record DecisionBatch(List<DecisionReport> events) {
    }
}
