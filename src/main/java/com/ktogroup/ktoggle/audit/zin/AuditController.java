package com.ktogroup.ktoggle.audit.zin;

import com.ktogroup.ktoggle.audit.AuditEntry;
import com.ktogroup.ktoggle.audit.AuditQuery;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.ChainVerification;
import com.ktogroup.ktoggle.audit.EntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit")
@RestController
@RequestMapping("/admin/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final int MAX_LIMIT = 200;

    private final AuditService auditService;

    @Operation(summary = "Search the control-plane audit trail (newest first, keyset pagination via beforeSeq)")
    @GetMapping
    public List<AuditEntry> search(@RequestParam(required = false) EntityType entityType,
                                   @RequestParam(required = false) String entityKey,
                                   @RequestParam(required = false) String actor,
                                   @RequestParam(required = false) UUID changeId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(required = false) Long beforeSeq,
                                   @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        return auditService.search(new AuditQuery(entityType, entityKey, actor, changeId, from, to, beforeSeq, bounded));
    }

    @Operation(summary = "Recompute the whole audit hash chain and report the first broken link, if any")
    @GetMapping("/verify")
    public ChainVerification verify() {
        return auditService.verifyChain();
    }
}
