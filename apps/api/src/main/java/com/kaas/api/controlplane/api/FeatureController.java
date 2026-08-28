package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.ControlPlaneService;
import com.kaas.api.controlplane.application.CreatedFeature;
import com.kaas.api.controlplane.domain.Feature;
import com.kaas.api.controlplane.domain.FeatureRevision;
import com.kaas.api.controlplane.domain.FeatureRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
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
@RequestMapping("/api/v1/projects/{projectId}/features")
public class FeatureController {
    private final ControlPlaneService service;
    private final TenantPrincipalResolver principals;

    public FeatureController(ControlPlaneService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<CreatedFeature> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateFeatureRequest request) {
        var creation = service.createFeature(
                principals.resolve(authentication),
                projectId,
                idempotencyKey,
                request.name(),
                request.logicalPath(),
                request.source());
        return ResponseEntity.created(URI.create(creation.location()))
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{featureId}")
    Feature get(Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID featureId) {
        return service.getFeature(principals.resolve(authentication), projectId, featureId);
    }

    @GetMapping
    PageResult<Feature> list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listFeatures(principals.resolve(authentication), projectId, page, size);
    }

    @PostMapping("/{featureId}/revisions")
    ResponseEntity<FeatureRevision> appendRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID featureId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRevisionRequest request) {
        var creation = service.appendRevision(
                principals.resolve(authentication), projectId, featureId, idempotencyKey, request.source());
        return ResponseEntity.created(URI.create(creation.location()))
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{featureId}/revisions/{revisionId}")
    FeatureRevision getRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID featureId,
            @PathVariable UUID revisionId) {
        return service.getRevision(principals.resolve(authentication), projectId, featureId, revisionId);
    }

    @GetMapping("/{featureId}/revisions")
    PageResult<FeatureRevisionSummary> listRevisions(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID featureId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listRevisions(principals.resolve(authentication), projectId, featureId, page, size);
    }

    public record CreateFeatureRequest(
            @NotBlank String name,
            @NotBlank @Size(max = 512) String logicalPath,
            @NotNull String source) {}

    public record CreateRevisionRequest(@NotNull String source) {}
}
