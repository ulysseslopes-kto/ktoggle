package com.ktogroup.ktoggle.apitoken.zin;

import com.ktogroup.ktoggle.apitoken.ApiToken;
import com.ktogroup.ktoggle.apitoken.ApiTokenRole;
import com.ktogroup.ktoggle.apitoken.ApiTokenService;
import com.ktogroup.ktoggle.apitoken.ApiTokenService.CreatedToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only. Use a token with {@code Authorization: Bearer ktg_...} on any {@code /admin/v1} endpoint its role allows. */
@RestController
@RequestMapping("/admin/v1/api-tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService service;

    @GetMapping
    public List<ApiToken> list() {
        return service.findAll();
    }

    @Operation(summary = "Create a token; the secret is in this response only and cannot be retrieved later")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedToken create(@Valid @RequestBody CreateTokenRequest request) {
        return service.create(request.name(), request.role(), request.expiresAt());
    }

    @Operation(summary = "Revoke a token immediately (idempotent)")
    @DeleteMapping("/{id}")
    public ApiToken revoke(@PathVariable UUID id) {
        return service.revoke(id);
    }

    /** @param expiresAt optional; at most one year away */
    public record CreateTokenRequest(@NotBlank String name, @NotNull ApiTokenRole role, Instant expiresAt) {
    }
}
