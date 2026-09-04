package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.shared.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps ownership of a claimed run honest: renews it while a worker is alive, and takes it back when one is not.
 *
 * <p>Claiming created the first state a run can be stuck in that somebody owns. Everything here exists so that
 * being owned is not a dead end — a worker that stops heartbeating loses the assignment, the assignment is
 * fenced, and the run reaches a terminal state that releases the capacity it was holding.
 */
@Service
public class WorkerLeaseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerLeaseService.class);

    /** The reconciler's audit actor for both fencing and settlement. */
    public static final String RECONCILER_ACTOR = "kaas.lease-reconciler";

    private final WorkerLeaseRepository leases;
    private final Duration leaseDuration;
    private final MeterRegistry meters;

    public WorkerLeaseService(
            WorkerLeaseRepository leases,
            MeterRegistry meters,
            @Value("${kaas.claim.lease-duration}") Duration leaseDuration) {
        this.leases = leases;
        this.meters = meters;
        this.leaseDuration = leaseDuration;
    }

    /**
     * Renews exactly the assignment the caller claims to hold.
     *
     * <p>Identity and epoch are checked together. An epoch alone would let any worker pose as the current owner;
     * an identity alone would let a restarted worker act under an assignment it has already lost. A heartbeat
     * changes no lifecycle state, bumps no version, and emits no event — it is not a transition, it is evidence
     * that the worker holding one is still there.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HeartbeatOutcome heartbeat(UUID runId, UUID attemptId, int epoch, String workerId) {
        if (runId == null || attemptId == null || epoch < 1 || workerId == null || workerId.isBlank()) {
            throw ApiException.validation("/assignmentEpoch", "A heartbeat names its run, attempt, and epoch.");
        }
        var locked = leases.lockOwnedByRun(runId);
        if (locked.isEmpty()) {
            // Either the run never existed, or it is no longer owned. Both are the same answer to a worker: the
            // assignment you are heartbeating is not the active one.
            return new HeartbeatOutcome(false, "NO_ACTIVE_ASSIGNMENT", null);
        }
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();
        if (!OWNED.contains(run.lifecycleState())) {
            // A stopping or completed run has already had its assignment taken away. A late heartbeat cannot
            // bring it back — that is the entire point of fencing. An EXECUTING run, by contrast, is precisely
            // the case a heartbeat exists for.
            return new HeartbeatOutcome(false, "RUN_NOT_OWNED", run);
        }
        if (!attempt.attemptId().equals(attemptId)
                || attempt.state() != ExecutionAttemptState.CLAIMED
                || attempt.assignment().epoch() != epoch
                || attempt.assignment().fenced()) {
            count("kaas.worker.heartbeat.rejected", "STALE_ASSIGNMENT");
            return new HeartbeatOutcome(false, "STALE_ASSIGNMENT", run);
        }
        if (workerId == null || !workerId.startsWith(WORKER_NAMESPACE)) {
            count("kaas.worker.heartbeat.rejected", "STALE_ASSIGNMENT");
            return new HeartbeatOutcome(false, "STALE_ASSIGNMENT", run);
        }
        if (attempt.assignment().acquired() && !attempt.assignment().workerId().equals(workerId)) {
            // Another worker holds it. Before acquisition existed, this comparison could not be made at all:
            // the stored worker id was one constant for the whole deployment, so every worker matched.
            count("kaas.worker.heartbeat.rejected", "STALE_ASSIGNMENT");
            return new HeartbeatOutcome(false, "STALE_ASSIGNMENT", run);
        }

        Instant at = leases.currentDatabaseTime();
        if (!attempt.assignment().acquired()) {
            // The first authenticated worker action binds the assignment — a heartbeat as readily as an
            // authorization. Losing the race means somebody else got there first, which is a stale assignment
            // from this caller's point of view and not an error.
            ExecutionAttempt acquiring = attempt.acquiredBy(workerId, at);
            if (!leases.acquire(locked.orElseThrow().organizationId(), acquiring)) {
                count("kaas.worker.heartbeat.rejected", "STALE_ASSIGNMENT");
                return new HeartbeatOutcome(false, "STALE_ASSIGNMENT", run);
            }
            attempt = acquiring;
        }
        if (attempt.assignment().expiredAt(at)) {
            // The lease is already gone. Renewing it here would let a worker take ownership back by being late
            // rather than by being correct, and would undo the reconciler's basis for fencing it.
            count("kaas.worker.heartbeat.rejected", "LEASE_EXPIRED");
            return new HeartbeatOutcome(false, "LEASE_EXPIRED", run);
        }
        if (!at.isAfter(attempt.assignment().lastHeartbeatAt())) {
            // A backwards NTP step, a failover to a standby whose clock trails, or two heartbeats inside one
            // microsecond. None of those is the caller's fault, and none should surface as a 500 — the honest
            // answer is that this renewal did not take, which the caller already knows how to handle.
            count("kaas.worker.heartbeat.rejected", "CLOCK_NOT_ADVANCED");
            return new HeartbeatOutcome(false, "CLOCK_NOT_ADVANCED", run);
        }
        ExecutionAttempt renewed = attempt.heartbeat(at, leaseDuration);
        if (!leases.renewLease(locked.orElseThrow().organizationId(), renewed)) {
            // The compare-and-set found the assignment already changed underneath the read. Losing that race is
            // not an error; it means somebody fenced this assignment between the lock and the write.
            count("kaas.worker.heartbeat.rejected", "STALE_ASSIGNMENT");
            return new HeartbeatOutcome(false, "STALE_ASSIGNMENT", run);
        }
        count("kaas.worker.heartbeat.accepted", "RENEWED");
        return new HeartbeatOutcome(true, "RENEWED", run);
    }

    /**
     * Takes the assignment back from a worker that stopped renewing it, and starts the run stopping.
     *
     * <p>Fencing and the lifecycle move commit together. Doing them separately would leave a window in which the
     * run is stopping while a worker still believes it owns the attempt — the exact inconsistency fencing exists
     * to prevent.
     *
     * @return whether this call is the one that fenced it
     */
    /**
     * Every state in which a worker holds the run and must therefore be able to renew its lease.
     *
     * <p>This was {@code CLAIMED} alone, which was complete until execution phases existed. The consequence was
     * severe and invisible: a heartbeat was REFUSED the moment a worker entered PROVISIONING, so the lease could
     * never be extended, and with a 30-second lease against a 30-minute execution budget every run longer than
     * half a minute was refused mid-flight and then recorded as having timed out during execution — a diagnosis
     * that was false in both halves. The database always permitted this; the attempt stays CLAIMED throughout,
     * so the heartbeat guard arm already applied. Only this check stood in the way.
     */
    /** Only a principal in this namespace may hold an assignment. */
    private static final String WORKER_NAMESPACE = "kaas.worker.";

    private static final java.util.Set<RunLifecycle> OWNED = java.util.EnumSet.of(
            RunLifecycle.CLAIMED,
            RunLifecycle.PROVISIONING,
            RunLifecycle.RUNNING,
            RunLifecycle.COLLECTING_RESULTS,
            RunLifecycle.PROCESSING_RESULTS);

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean fenceExpired(UUID runId) {
        var locked = leases.lockOwnedByRun(runId);
        if (locked.isEmpty()) {
            return false;
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();
        // Fencing follows the lease into the execution phases too. Widening the heartbeat without widening this
        // would mean a worker that died mid-execution kept its assignment until the phase deadline — up to
        // thirty minutes of held admission capacity for a worker known dead within one.
        if (!OWNED.contains(run.lifecycleState()) || attempt.state() != ExecutionAttemptState.CLAIMED) {
            return false;
        }
        Instant at = leases.currentDatabaseTime();
        if (!attempt.assignment().expiredAt(at)) {
            // A heartbeat committed between selection and this lock. The worker is alive; leave it alone.
            return false;
        }
        Instant stoppedAt = at.isBefore(run.updatedAt()) ? run.updatedAt() : at;
        TestRun stopping = run.stopping(StopReason.LEASE_LOST, null, stoppedAt);
        leases.persistStop(
                organizationId, run, stopping, attempt.fenced(stoppedAt), UUID.randomUUID(), RECONCILER_ACTOR);
        count("kaas.worker.lease.expired", "LEASE_LOST");
        LOGGER.atWarn()
                .addKeyValue("event", "WORKER_LEASE_LOST")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("runId", runId)
                .addKeyValue("attemptId", attempt.attemptId())
                .addKeyValue("assignmentEpoch", attempt.assignment().epoch())
                .log("Fenced an assignment whose lease expired; the run is stopping");
        return true;
    }

    /**
     * Settles a stopping run.
     *
     * <p>Canonically this waits a stop-acknowledgement grace. Nothing in this slice can acknowledge a stop —
     * there is no sandbox, no stop command, and no worker protocol to carry one — so waiting would be latency
     * for an event that provably cannot arrive. The grace becomes real when something exists to send it; until
     * then STOPPING is a state the run genuinely passes through, with its own version and event, and it is
     * settled on the next pass rather than after a timer.
     *
     * @return whether this call is the one that settled it
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean settleStopping(UUID runId) {
        var locked = leases.lockOwnedByRun(runId);
        if (locked.isEmpty()) {
            return false;
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        if (run.lifecycleState() != RunLifecycle.STOPPING) {
            return false;
        }
        Instant at = leases.currentDatabaseTime();
        Instant settledAt = at.isBefore(run.updatedAt()) ? run.updatedAt() : at;
        TestRun settled = run.settled(settledAt);
        leases.persistSettlement(organizationId, run, settled, UUID.randomUUID());
        count("kaas.run.terminated", settled.terminationReason().name());
        LOGGER.atInfo()
                .addKeyValue("event", "RUN_TERMINATED")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("projectId", settled.projectId())
                .addKeyValue("runId", runId)
                .addKeyValue("runVersion", settled.runVersion())
                .addKeyValue("reason", settled.terminationReason().name())
                .log("Settled a stopping run and released its capacity");
        return true;
    }

    private void count(String name, String reason) {
        Counter.builder(name).tag("reason", reason).register(meters).increment();
    }

    /** Whether the lease was renewed, and a bounded reason when it was not. */
    public record HeartbeatOutcome(boolean renewed, String reason, TestRun run) {}
}
