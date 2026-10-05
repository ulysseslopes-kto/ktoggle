package com.ktogroup.ktoggle.delivery.zin;

import com.ktogroup.ktoggle.delivery.DeliveryLogEntry;
import com.ktogroup.ktoggle.delivery.DeliveryLogPersistencePort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bundles")
@RestController
@RequiredArgsConstructor
public class DeliveryLogController {

    private static final int MAX_LIMIT = 500;

    private final DeliveryLogPersistencePort deliveryLog;

    @Operation(summary = "Which bundles a client key actually downloaded/received, aggregated per hour and pod")
    @GetMapping("/admin/v1/sdk-connections/{clientKey}/deliveries")
    public List<DeliveryLogEntry> deliveries(@PathVariable String clientKey,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @RequestParam(defaultValue = "100") int limit) {
        return deliveryLog.find(clientKey, from, to, Math.clamp(limit, 1, MAX_LIMIT));
    }
}
