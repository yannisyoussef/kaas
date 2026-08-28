package com.kaas.api.internal;

import com.kaas.api.controlplane.application.WorkerLeaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal service surface. Not part of the public API contract and deliberately not in the OpenAPI
 * document: its audience is the platform's own workers, not tenants.
 *
 * <p>A heartbeat is not a lifecycle transition. It bumps no version, emits no public event, and produces no
 * representation a tenant could cache — it is the worker saying it is still there, and the only thing it can
 * move is the expiry of the assignment it already holds.
 */
@Validated
@RestController
@RequestMapping("/internal/v1")
class WorkerLeaseController {
    private final WorkerLeaseService leases;

    WorkerLeaseController(WorkerLeaseService leases) {
        this.leases = leases;
    }

    /**
     * Renews one assignment.
     *
     * <p>The worker identity comes from the authenticated service principal, never from the body. The epoch does
     * come from the body — it has to, because it names which assignment the caller believes it holds — but it is
     * checked against the active one rather than trusted, so naming a different epoch fails rather than
     * succeeding as somebody else.
     *
     * <p>A rejected heartbeat is 409 rather than 404: the run exists, and the honest statement is that this
     * assignment is not the active one. There is no tenant to conceal anything from here — the caller is the
     * platform.
     */
    @PostMapping("/runs/{runId}/attempts/{attemptId}/heartbeat")
    ResponseEntity<Void> heartbeat(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody HeartbeatRequest request) {
        var outcome = leases.heartbeat(runId, attemptId, request.assignmentEpoch(), authentication.getName());
        return outcome.renewed()
                ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();
    }

    /** The fencing token the caller believes it holds. Everything else about its identity comes from the token. */
    record HeartbeatRequest(@NotNull @Min(1) @Max(1000) Integer assignmentEpoch) {}
}
