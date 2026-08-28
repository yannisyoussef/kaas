package com.kaas.api.controlplane.api;

import com.kaas.api.controlplane.application.RunIntentService;
import com.kaas.api.controlplane.application.RunTerminationService;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.security.TenantPrincipalResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
    private final RunTerminationService terminations;
    private final TenantPrincipalResolver principals;

    public RunController(
            RunIntentService service, RunTerminationService terminations, TenantPrincipalResolver principals) {
        this.service = service;
        this.terminations = terminations;
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

    /**
     * Cancellation is a sub-resource rather than a mutation of the run, because a run is evidence: the tenant
     * creates a request to stop, and the control plane records what became of it.
     *
     * <p>There is no {@code Idempotency-Key} because the run itself is the idempotency scope. Repeating the
     * request returns the same cancelled run and writes nothing, and a key scoped to this run's own path could
     * never catch a mistake that state already catches.
     */
    @PostMapping("/runs/{runId}/cancellations")
    ResponseEntity<TestRun> cancel(
            Authentication authentication,
            @PathVariable UUID runId,
            @Valid @RequestBody CancellationRequest request) {
        TestRun run = terminations.cancel(principals.resolve(authentication), runId);
        return ResponseEntity.ok()
                .location(URI.create("/api/v1/runs/" + runId))
                .eTag("run-" + run.runVersion())
                .cacheControl(CacheControl.noStore())
                .body(run);
    }

    @GetMapping("/runs/{runId}/snapshot")
    ResponseEntity<RunSnapshot> snapshot(Authentication authentication, @PathVariable UUID runId) {
        RunSnapshot snapshot = service.snapshot(principals.resolve(authentication), runId);
        return ResponseEntity.ok()
                .eTag("snapshot-" + snapshot.snapshotDigest().substring("sha256:".length()))
                .cacheControl(CacheControl.noStore())
                .body(snapshot);
    }

    /**
     * The only reason a tenant can give. Anything else describes a decision the platform made, not one they did,
     * and an open string would let a client write its own cause into an audited record.
     */
    public record CancellationRequest(@NotNull @Pattern(regexp = "USER_REQUESTED") String reason) {}

    public record CreateRunRequest(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> featureRevisionIds,
            @NotNull UUID runProfileRevisionId) {}
}
