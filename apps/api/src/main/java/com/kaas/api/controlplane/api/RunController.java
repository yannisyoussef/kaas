package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.RunIntentService;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1")
public class RunController {
    private final RunIntentService service;
    private final TenantPrincipalResolver principals;

    public RunController(RunIntentService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping("/projects/{projectId}/runs")
    ResponseEntity<TestRun> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request) {
        var creation = service.create(
                principals.resolve(authentication), projectId, idempotencyKey,
                request.featureRevisionIds(), request.runProfileRevisionId());
        return ResponseEntity.accepted()
                .location(URI.create(creation.location()))
                .eTag("run-" + creation.value().runVersion())
                .cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.value());
    }

    @GetMapping("/runs/{runId}")
    ResponseEntity<TestRun> get(Authentication authentication, @PathVariable UUID runId) {
        TestRun run = service.get(principals.resolve(authentication), runId);
        return ResponseEntity.ok()
                .eTag("run-" + run.runVersion())
                .cacheControl(CacheControl.noStore())
                .body(run);
    }

    @GetMapping("/projects/{projectId}/runs")
    ResponseEntity<PageResult<TestRun>> list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.list(principals.resolve(authentication), projectId, page, size));
    }

    @GetMapping("/runs/{runId}/snapshot")
    ResponseEntity<RunSnapshot> snapshot(Authentication authentication, @PathVariable UUID runId) {
        RunSnapshot snapshot = service.snapshot(principals.resolve(authentication), runId);
        return ResponseEntity.ok()
                .eTag("snapshot-" + snapshot.snapshotDigest().substring("sha256:".length()))
                .cacheControl(CacheControl.noStore())
                .body(snapshot);
    }

    public record CreateRunRequest(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> featureRevisionIds,
            @NotNull UUID runProfileRevisionId) {}
}
