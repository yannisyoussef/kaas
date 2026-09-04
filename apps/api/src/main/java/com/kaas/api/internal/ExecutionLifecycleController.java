package com.kaas.api.internal;

import com.kaas.api.execution.application.ExecutionPhaseService;
import com.kaas.api.execution.application.ExecutionResultService;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.ExecutionPhase;
import com.kaas.api.execution.domain.ExecutionResultPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal execution-lifecycle surface: how an assigned worker reports progress and submits evidence.
 *
 * <p><strong>What a caller may say.</strong> The run, the attempt, the assignment epoch it believes it holds,
 * which phase it is entering, and — once — the result document. There is no field for an organization, a worker
 * identity, a lifecycle state, a deadline, a termination reason, an outcome, or a run version. Those are either
 * derived from authoritative state or refused; a request cannot widen its own authority because there is
 * nowhere to write it.
 *
 * <p>Worker identity comes from the authenticated service principal on the internal filter chain, the same
 * mechanism the heartbeat and the authorization endpoint use. It is not mTLS, and this comment says so rather
 * than implying a stronger boundary than the deployment has.
 *
 * <p><strong>The organization is not an input.</strong> It is read from the run itself. Accepting it from the
 * caller would be accepting a claim about ownership from the party whose ownership is the thing in question.
 */
@Validated
@RestController
@RequestMapping("/internal/v1")
class ExecutionLifecycleController {

    private final ExecutionPhaseService phases;
    private final ExecutionResultService results;

    ExecutionLifecycleController(ExecutionPhaseService phases, ExecutionResultService results) {
        this.phases = phases;
        this.results = results;
    }

    /**
     * Advances the run into the phase named, if this caller still owns it.
     *
     * <p>Not idempotent in the strict sense, and deliberately not pretending to be: a repeat of an advance that
     * already happened is refused as {@code PHASE_NOT_ENTERABLE} rather than answered with success. Reporting
     * success would hide a worker that had lost track of where it was, and the distinct code is what lets an
     * honest worker recognise its own retry.
     */
    @PostMapping("/runs/{runId}/attempts/{attemptId}/phases")
    ResponseEntity<Map<String, Object>> advance(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody PhaseRequest request) {

        var decision = phases.advance(
                authentication.getName(), runId, attemptId, request.assignmentEpoch(), request.phase(),
                request.sandboxReference());
        if (decision.denial().isPresent()) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("code", decision.denial().orElseThrow().name());
            // Only when the service chose to disclose it, which it does only after the caller has proved it
            // holds this assignment.
            decision.currentRun().ifPresent(current -> {
                refused.put("lifecycleState", current.lifecycleState().name());
                // The start instant travels with the refusal too, so a worker whose RUNNING advance landed but
                // whose response was lost can still echo the control plane's own instant back in its result.
                // Without it the retry path fails closed on the one phase where it matters most.
                if (current.executionStartedAt() != null) {
                    refused.put("executionStartedAt", current.executionStartedAt().toString());
                }
            });
            return ResponseEntity.status(409)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(refused);
        }
        var run = decision.run().orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.runId().toString());
        body.put("runVersion", run.runVersion());
        body.put("lifecycleState", run.lifecycleState().name());
        // The worker needs this to know how long it has. Returned rather than assumed, because the budget is a
        // platform decision the worker must not hold its own copy of.
        body.put("phaseDeadlineAt", run.phaseDeadlineAt().toString());
        // The instant the control plane stamped when execution began, returned so the runner can echo it
        // exactly. The result submission is checked against this value, and a runner that measured its own
        // start instant instead would disagree with it by however far this host's clock has drifted — so every
        // submission would be refused as a provenance mismatch, for a reason with nothing to do with the run.
        if (run.executionStartedAt() != null) {
            body.put("executionStartedAt", run.executionStartedAt().toString());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /**
     * Reports that this assignment's infrastructure failed, stopping the run.
     *
     * <p>Distinct from a result on purpose. A result carries a test outcome; this carries none, because nothing
     * ran to completion. Without this endpoint a worker that watched its sandbox die had no way to say so, and
     * the run was reclaimed by a deadline and recorded as a timeout — blaming the tenant's tests for taking too
     * long when the platform had failed within seconds.
     */
    @PostMapping("/runs/{runId}/attempts/{attemptId}/infrastructure-failures")
    ResponseEntity<Void> reportInfrastructureFailure(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody InfrastructureFailureRequest request) {

        var denial = phases.reportInfrastructureFailure(
                authentication.getName(), runId, attemptId, request.assignmentEpoch(), request.detail());
        if (denial.isPresent()) {
            return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();
        }
        // 204: the run stopped, and there is nothing to say about it that the caller does not already know.
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Submits the evidence one execution produced, and completes the run on it. */
    @PostMapping("/runs/{runId}/attempts/{attemptId}/results")
    ResponseEntity<Map<String, Object>> submit(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody ResultRequest request) {

        var decision = results.submit(
                authentication.getName(), runId, attemptId, request.assignmentEpoch(), request.commandId(),
                request.document());
        if (decision.denial().isPresent()) {
            return refusal(decision.denial().orElseThrow());
        }
        var run = decision.run().orElseThrow();
        var result = decision.result().orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultId", result.resultId().toString());
        // The digest the control plane computed, not one the worker supplied. A worker can compare it against
        // its own to detect a document altered in transit; it could not do that with a value it had sent.
        body.put("resultDigest", result.resultDigest());
        body.put("runId", run.runId().toString());
        body.put("runVersion", run.runVersion());
        body.put("lifecycleState", run.lifecycleState().name());
        body.put("testOutcome", run.testOutcome().name());
        body.put("infrastructureOutcome", run.infrastructureOutcome().name());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /**
     * Every refusal is 409, and every refusal carries only its code.
     *
     * <p>One status for all of them for the same reason the authorization endpoint uses one: distinguishing
     * "stale" from "wrong phase" from "does not exist" by status code would make this endpoint an oracle. The
     * body carries the code because the caller is the platform's own worker and needs to act differently on
     * each — but a code in a body is not a status a proxy or a cache will treat specially.
     */
    private ResponseEntity<Map<String, Object>> refusal(ExecutionDenial denial) {
        return ResponseEntity.status(409)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Map.of("code", denial.name()));
    }

    /**
     * A phase advance.
     *
     * <p>{@code phase} is an {@link ExecutionPhase}, which is a smaller vocabulary than the lifecycle: a worker
     * cannot ask for {@code QUEUED}, {@code STOPPING}, or {@code COMPLETED} because those are not values this
     * type can hold. Making the illegal requests unspellable is stronger than validating them away.
     */
    record PhaseRequest(
            @NotNull @Min(1) @Max(1000) Integer assignmentEpoch,
            @NotNull ExecutionPhase phase,
            // Opaque and bounded. Recorded for operator diagnosis, never interpreted, and never used to find
            // the sandbox again — the orphan reconciler matches on labels it applied itself.
            @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]*$") String sandboxReference) {}

    /**
     * An infrastructure failure report.
     *
     * <p>{@code detail} is bounded and character-restricted: it is the worker's own description of its sandbox,
     * it reaches the platform log, and nothing that reaches a log should be an unbounded caller-supplied
     * string. It carries no tenant content — the workload's own output is never put here.
     */
    record InfrastructureFailureRequest(
            @NotNull @Min(1) @Max(1000) Integer assignmentEpoch,
            @NotNull @Size(max = 256) @Pattern(regexp = "^[A-Za-z0-9 .,:;()/_-]*$") String detail) {}

    /** A result submission. */
    record ResultRequest(
            @NotNull @Min(1) @Max(1000) Integer assignmentEpoch,
            // Which command this result answers. Compared against the authorization's own record, so a document
            // answering a command this assignment was never issued is refused.
            @NotNull UUID commandId,
            @NotNull @Size(max = ExecutionResultPolicy.MAX_DOCUMENT_BYTES) String document) {}
}
