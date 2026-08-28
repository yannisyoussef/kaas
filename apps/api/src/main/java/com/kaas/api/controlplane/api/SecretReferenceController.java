package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.ConfigurationService;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.SecretReference;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/secret-references")
public class SecretReferenceController {
    private final ConfigurationService service;
    private final TenantPrincipalResolver principals;

    public SecretReferenceController(ConfigurationService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<SecretReference> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSecretReferenceRequest request) {
        var creation = service.createSecretReference(
                principals.resolve(authentication), projectId, idempotencyKey, request.name());
        return ResponseEntity.created(URI.create(creation.location()))
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{secretReferenceId}")
    ResponseEntity<SecretReference> get(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID secretReferenceId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getSecretReference(
                        principals.resolve(authentication), projectId, secretReferenceId));
    }

    @GetMapping
    ResponseEntity<PageResult<SecretReference>> list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listSecretReferences(
                        principals.resolve(authentication), projectId, page, size));
    }

    public record CreateSecretReferenceRequest(@NotBlank String name) {}
}
