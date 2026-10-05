package com.ktogroup.ktoggle.bundle.zin;

import com.ktogroup.ktoggle.audit.ChainVerification;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleActivation;
import com.ktogroup.ktoggle.bundle.BundleService;
import com.ktogroup.ktoggle.bundle.BundleService.VerifiedBundle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bundles")
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class BundleController {

    private static final int MAX_LIMIT = 200;

    private final BundleService bundleService;

    @Operation(summary = "Get a bundle by hash; it is verified (hash + signature) before being returned")
    @GetMapping("/bundles/{hash}")
    public VerifiedBundle get(@PathVariable String hash) {
        return bundleService.get(hash);
    }

    @GetMapping("/sdk-connections/{clientKey}/bundles")
    public List<Bundle> bundles(@PathVariable String clientKey, @RequestParam(defaultValue = "50") int limit) {
        return bundleService.bundles(clientKey, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/sdk-connections/{clientKey}/activations")
    public List<BundleActivation> activations(@PathVariable String clientKey, @RequestParam(defaultValue = "50") int limit) {
        return bundleService.activations(clientKey, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @Operation(summary = "Which bundle the client key was serving at the given instant")
    @GetMapping("/sdk-connections/{clientKey}/activations/at")
    public BundleActivation activationAt(@PathVariable String clientKey, @RequestParam Instant instant) {
        return bundleService.activationAt(clientKey, instant);
    }

    @Operation(summary = "Recompute the activation chain and verify every bundle it references")
    @GetMapping("/sdk-connections/{clientKey}/activations/verify")
    public ChainVerification verify(@PathVariable String clientKey) {
        return bundleService.verifyChain(clientKey);
    }

    @Operation(summary = "Emergency rollback: re-activate a previous bundle and pin the connection (X-Ktoggle-Reason required)")
    @PostMapping("/sdk-connections/{clientKey}/bundles/{hash}/activate")
    public BundleActivation rollback(@PathVariable String clientKey, @PathVariable String hash) {
        return bundleService.rollback(clientKey, hash);
    }

    @Operation(summary = "Release the pin and publish the current configuration")
    @PostMapping("/sdk-connections/{clientKey}/unpin")
    public List<BundleActivation> unpin(@PathVariable String clientKey) {
        return bundleService.unpin(clientKey);
    }
}
