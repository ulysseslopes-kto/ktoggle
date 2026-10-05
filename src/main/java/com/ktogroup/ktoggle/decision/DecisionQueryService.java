package com.ktogroup.ktoggle.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.decision.DecisionEventPersistencePort.DecisionQuery;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DecisionQueryService {

    private final DecisionEventPersistencePort persistence;
    private final AttributeDigester digester;

    public List<DecisionEvent> search(DecisionQuery query) {
        return persistence.search(query);
    }

    public DecisionEvent get(UUID eventId) {
        return persistence.findById(eventId).orElseThrow(() -> new NotFoundException("Decision event", eventId.toString()));
    }

    /**
     * Proves (or disproves) that {@code claimedAttributes} are exactly the attributes the SDK evaluated for this
     * decision. Combined with a replay of the event's bundle, this reproduces the decision end to end.
     */
    public boolean attributesMatch(UUID eventId, JsonNode claimedAttributes) {
        DecisionEvent event = get(eventId);
        return digester.matches(claimedAttributes, event.attributesDigest(), event.digestKeyId());
    }
}
