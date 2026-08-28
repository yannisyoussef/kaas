package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.ConfigurationService;
import com.kaas.api.controlplane.application.CreatedRunProfile;
import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunProfile;
import com.kaas.api.controlplane.domain.RunProfileRevision;
import com.kaas.api.controlplane.domain.RunProfileRevisionSummary;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
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
@RequestMapping("/api/v1/projects/{projectId}/run-profiles")
public class RunProfileController {
    private final ConfigurationService service;
    private final TenantPrincipalResolver principals;

    public RunProfileController(ConfigurationService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<CreatedRunProfile> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunProfileRequest request) {
        var creation = service.createRunProfile(
                principals.resolve(authentication),
                projectId,
                idempotencyKey,
                request.name(),
                request.environmentRevisionId(),
                request.selection().tags(),
                request.parallelism(),
                retry(request.scenarioRetry()),
                request.executionTimeoutSeconds(),
                policy(request.artifactPolicy()),
                overrides(request.configurationOverrides()));
        return ResponseEntity.created(URI.create(creation.location()))
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{runProfileId}")
    ResponseEntity<RunProfile> get(
            Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID runProfileId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getRunProfile(
                        principals.resolve(authentication), projectId, runProfileId));
    }

    @GetMapping
    ResponseEntity<PageResult<RunProfile>> list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listRunProfiles(principals.resolve(authentication), projectId, page, size));
    }

    @PostMapping("/{runProfileId}/revisions")
    ResponseEntity<RunProfileRevision> appendRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID runProfileId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunProfileRevisionRequest request) {
        var creation = service.appendRunProfileRevision(
                principals.resolve(authentication),
                projectId,
                runProfileId,
                idempotencyKey,
                request.environmentRevisionId(),
                request.selection().tags(),
                request.parallelism(),
                retry(request.scenarioRetry()),
                request.executionTimeoutSeconds(),
                policy(request.artifactPolicy()),
                overrides(request.configurationOverrides()));
        return ResponseEntity.created(URI.create(creation.location()))
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{runProfileId}/revisions/{revisionId}")
    ResponseEntity<RunProfileRevision> getRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID runProfileId,
            @PathVariable UUID revisionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getRunProfileRevision(
                        principals.resolve(authentication), projectId, runProfileId, revisionId));
    }

    @GetMapping("/{runProfileId}/revisions")
    ResponseEntity<PageResult<RunProfileRevisionSummary>> listRevisions(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID runProfileId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listRunProfileRevisions(
                        principals.resolve(authentication), projectId, runProfileId, page, size));
    }

    private static ScenarioRetry retry(ScenarioRetryRequest request) {
        return new ScenarioRetry(request.maxAttempts(), request.delayMilliseconds());
    }

    private static ArtifactPolicy policy(ArtifactPolicyRequest request) {
        return new ArtifactPolicy(request.types(), request.maxArtifactBytes(), request.maxTotalBytes());
    }

    private static List<ConfigurationVariable> overrides(List<VariableRequest> overrides) {
        return overrides.stream()
                .map(override -> new ConfigurationVariable(override.key(), override.type(), override.value()))
                .toList();
    }

    public record CreateRunProfileRequest(
            @NotBlank String name,
            @NotNull UUID environmentRevisionId,
            @NotNull @Valid SelectionRequest selection,
            @NotNull @Min(1) @Max(32) Integer parallelism,
            @NotNull @Valid ScenarioRetryRequest scenarioRetry,
            @NotNull @Min(1) @Max(3600) Integer executionTimeoutSeconds,
            @NotNull @Valid ArtifactPolicyRequest artifactPolicy,
            @NotNull @Size(max = 100) List<@Valid VariableRequest> configurationOverrides) {}

    public record CreateRunProfileRevisionRequest(
            @NotNull UUID environmentRevisionId,
            @NotNull @Valid SelectionRequest selection,
            @NotNull @Min(1) @Max(32) Integer parallelism,
            @NotNull @Valid ScenarioRetryRequest scenarioRetry,
            @NotNull @Min(1) @Max(3600) Integer executionTimeoutSeconds,
            @NotNull @Valid ArtifactPolicyRequest artifactPolicy,
            @NotNull @Size(max = 100) List<@Valid VariableRequest> configurationOverrides) {}

    public record SelectionRequest(@NotNull @Size(max = 100) List<@NotBlank String> tags) {}

    public record ScenarioRetryRequest(
            @NotNull @Min(1) @Max(5) Integer maxAttempts,
            @NotNull @Min(0) @Max(30000) Integer delayMilliseconds) {}

    public record ArtifactPolicyRequest(
            @NotNull @Size(max = 4) List<@NotNull ArtifactType> types,
            @NotNull @Min(0) @Max(104857600) Long maxArtifactBytes,
            @NotNull @Min(0) @Max(524288000) Long maxTotalBytes) {}

    public record VariableRequest(
            @NotBlank String key, @NotNull ConfigurationValueType type, @NotNull Object value) {}
}
