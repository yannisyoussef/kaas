package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.ConfigurationService;
import com.kaas.api.controlplane.application.CreatedEnvironment;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.Environment;
import com.kaas.api.controlplane.domain.EnvironmentRevision;
import com.kaas.api.controlplane.domain.EnvironmentRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.SecretBinding;
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
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {
    private final ConfigurationService service;
    private final TenantPrincipalResolver principals;

    public EnvironmentController(ConfigurationService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<CreatedEnvironment> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateEnvironmentRequest request) {
        var creation = service.createEnvironment(
                principals.resolve(authentication),
                projectId,
                idempotencyKey,
                request.name(),
                variables(request.variables()),
                bindings(request.secretBindings()));
        return ResponseEntity.created(URI.create(creation.location()))
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{environmentId}")
    ResponseEntity<Environment> get(
            Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getEnvironment(
                        principals.resolve(authentication), projectId, environmentId));
    }

    @GetMapping
    ResponseEntity<PageResult<Environment>> list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listEnvironments(principals.resolve(authentication), projectId, page, size));
    }

    @PostMapping("/{environmentId}/revisions")
    ResponseEntity<EnvironmentRevision> appendRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateEnvironmentRevisionRequest request) {
        var creation = service.appendEnvironmentRevision(
                principals.resolve(authentication),
                projectId,
                environmentId,
                idempotencyKey,
                variables(request.variables()),
                bindings(request.secretBindings()));
        return ResponseEntity.created(URI.create(creation.location()))
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{environmentId}/revisions/{revisionId}")
    ResponseEntity<EnvironmentRevision> getRevision(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID revisionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getEnvironmentRevision(
                        principals.resolve(authentication), projectId, environmentId, revisionId));
    }

    @GetMapping("/{environmentId}/revisions")
    ResponseEntity<PageResult<EnvironmentRevisionSummary>> listRevisions(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listEnvironmentRevisions(
                        principals.resolve(authentication), projectId, environmentId, page, size));
    }

    private static List<ConfigurationVariable> variables(List<VariableRequest> variables) {
        return variables.stream()
                .map(variable -> new ConfigurationVariable(variable.key(), variable.type(), variable.value()))
                .toList();
    }

    private static List<SecretBinding> bindings(List<SecretBindingRequest> bindings) {
        return bindings.stream()
                .map(binding -> new SecretBinding(binding.key(), binding.secretReferenceId()))
                .toList();
    }

    public record CreateEnvironmentRequest(
            @NotBlank String name,
            @NotNull @Size(max = 100) List<@Valid VariableRequest> variables,
            @NotNull @Size(max = 50) List<@Valid SecretBindingRequest> secretBindings) {}

    public record CreateEnvironmentRevisionRequest(
            @NotNull @Size(max = 100) List<@Valid VariableRequest> variables,
            @NotNull @Size(max = 50) List<@Valid SecretBindingRequest> secretBindings) {}

    public record VariableRequest(
            @NotBlank String key, @NotNull ConfigurationValueType type, @NotNull Object value) {}

    public record SecretBindingRequest(@NotBlank String key, @NotNull UUID secretReferenceId) {}
}
