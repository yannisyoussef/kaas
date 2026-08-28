package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.ControlPlaneService;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.Project;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ControlPlaneService service;
    private final TenantPrincipalResolver principals;

    public ProjectController(ControlPlaneService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<Project> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateProjectRequest request) {
        var creation = service.createProject(principals.resolve(authentication), idempotencyKey, request.name());
        return ResponseEntity.created(URI.create(creation.location()))
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/{projectId}")
    Project get(Authentication authentication, @PathVariable UUID projectId) {
        return service.getProject(principals.resolve(authentication), projectId);
    }

    @GetMapping
    PageResult<Project> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listProjects(principals.resolve(authentication), page, size);
    }

    public record CreateProjectRequest(@NotBlank String name) {}
}
