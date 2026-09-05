package com.kaas.api.internal;

import com.kaas.api.controlplane.application.WorkerLeaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
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
    ResponseEntity<Map<String, Object>> heartbeat(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody HeartbeatRequest request) {
        var outcome = leases.heartbeat(runId, attemptId, request.assignmentEpoch(), authentication.getName());

        // THE DECISION, NOT A BOOLEAN.
        //
        // This used to answer 204 or 409 with an empty body, which threw away everything the service had just
        // worked out. The consequence was not cosmetic: a worker could not tell "you have been fenced" from
        // "the database clock stepped backwards", so it could not safely act on either -- and the design
        // conclusion drawn from that was that a worker should not act on heartbeats at all. A workload could
        // then keep running after its authority ended, until some later phase transition happened to notice.
        //
        // The reason is a closed vocabulary the service already produced. It is safe to disclose here: the
        // caller is the platform's own worker, authenticated as a service principal, and this surface is not
        // in the public OpenAPI document.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", outcome.reason());
        // THE LEASE WINDOW, BOTH INSTANTS FROM THE DATABASE'S CLOCK.
        //
        // Returned as a pair on purpose. A worker must never compare its own wall clock against a database
        // instant -- two hosts differ by whatever NTP has not corrected -- so what it takes from here is the
        // DURATION between these two, computed inside one clock domain, which it then holds against its own
        // monotonic clock. Absent when the renewal was refused before any lease was read.
        if (outcome.serverNow() != null) {
            body.put("serverNow", outcome.serverNow().toString());
            body.put("leaseExpiresAt", outcome.leaseExpiresAt().toString());
        }
        return ResponseEntity.status(outcome.renewed() ? 200 : 409)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /** The fencing token the caller believes it holds. Everything else about its identity comes from the token. */
    record HeartbeatRequest(@NotNull @Min(1) @Max(1000) Integer assignmentEpoch) {}
}
