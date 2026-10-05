package com.ktogroup.ktoggle.decision.zin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.decision.DecisionEvent;
import com.ktogroup.ktoggle.decision.DecisionEventPersistencePort.DecisionQuery;
import com.ktogroup.ktoggle.decision.DecisionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Decisions")
@RestController
@RequestMapping("/admin/v1/decisions")
@RequiredArgsConstructor
public class DecisionController {

    private static final int MAX_LIMIT = 500;

    private final DecisionQueryService queryService;

    @GetMapping
    public List<DecisionEvent> search(@RequestParam(required = false) String clientKey,
                                      @RequestParam(required = false) String featureKey,
                                      @RequestParam(required = false) String bundleHash,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(defaultValue = "100") int limit) {
        return queryService.search(new DecisionQuery(clientKey, featureKey, bundleHash, from, to, Math.clamp(limit, 1, MAX_LIMIT)));
    }

    @GetMapping("/{eventId}")
    public DecisionEvent get(@PathVariable UUID eventId) {
        return queryService.get(eventId);
    }

    @Operation(summary = "Check whether the given attributes are exactly those the SDK evaluated for this decision")
    @PostMapping("/{eventId}/verify-attributes")
    public AttributesVerification verifyAttributes(@PathVariable UUID eventId, @RequestBody JsonNode attributes) {
        return new AttributesVerification(eventId, queryService.attributesMatch(eventId, attributes));
    }

    public record AttributesVerification(UUID eventId, boolean matches) {
    }
}
