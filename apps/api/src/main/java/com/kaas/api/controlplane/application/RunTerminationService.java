package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import com.kaas.api.controlplane.domain.TerminationReason;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ends runs that no worker owns. Both ways in — a tenant asking, and a queue deadline passing — are the same
 * transition and share one implementation, because the only thing that differs between them is why.
 *
 * <p>This is the first code that can make a run terminal, and it is what turns the admission ceiling from an
 * availability ceiling back into a capacity ceiling: before this, an organization that filled its quota could
 * never create another run, because nothing could ever leave.
 */
@Service
public class RunTerminationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunTerminationService.class);

    /** The reaper acts on its own authority, not a tenant's, so it is named rather than borrowing a principal. */
    public static final String REAPER_ACTOR = "kaas.queue-reaper";

    private final RunIntentRepository runs;
    private final RunTerminationRepository terminations;
    private final WorkerLeaseRepository leases;
    private final Clock clock;
    private final MeterRegistry meters;

    public RunTerminationService(
            RunIntentRepository runs,
            RunTerminationRepository terminations,
            WorkerLeaseRepository leases,
            Clock clock,
            MeterRegistry meters) {
        this.runs = runs;
        this.terminations = terminations;
        this.leases = leases;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Cancels a run at the tenant's request. Cancelling unowned work needs nobody's cooperation, so the run is
     * already over by the time this returns.
     *
     * <p>Idempotency is by state, not by key. Repeating the request returns the run that was already cancelled and
     * writes nothing — a client cannot end up with two cancellations because there is only one run to cancel. No
     * {@code Idempotency-Key} is read, required, or recorded: one would be scoped to this run's own path and so
     * could never catch a mistake the state check does not already catch.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TestRun cancel(TenantPrincipal principal, UUID runId) {
        Instant requestedAt = clock.instant();
        UUID organizationId = principal.organizationId();
        var locked = terminations.lockTerminable(organizationId, runId);
        if (locked.isEmpty()) {
            // Nothing unowned to cancel. A run a worker holds takes the longer road: its assignment has to be
            // fenced before it can end, so cancelling it is a request rather than a completion.
            var owned = requestStop(principal, runId);
            if (owned.isPresent()) {
                return owned.orElseThrow();
            }
            return alreadyDecided(organizationId, runId);
        }
        TestRun previous = locked.orElseThrow();
        TestRun cancelled = previous.cancelled(requestedAt, terminalInstant(previous, requestedAt));
        terminations.persistTermination(
                organizationId, previous, cancelled, UUID.randomUUID(), principal.principalId());
        terminated(organizationId, cancelled, TerminationReason.USER_REQUESTED);
        return cancelled;
    }

    /**
     * Cancels a run a worker already owns.
     *
     * <p>Unlike unowned work this cannot finish in one step. An assignment exists, and it has to be taken back
     * before the run can be called over — so the request is recorded, the assignment is fenced, and the run
     * enters STOPPING for the reconciler to settle. That is why this returns a pending run rather than a
     * terminal one, and why the endpoint answers 202 for it.
     *
     * @return the run in STOPPING, or empty when it was not owned and the caller should fall back to cancelling
     *     unowned work
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TestRun> requestStop(TenantPrincipal principal, UUID runId) {
        Instant requestedAt = clock.instant();
        UUID organizationId = principal.organizationId();
        var locked = leases.lockOwnedByRun(runId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        var owned = locked.orElseThrow();
        // Tenant scoping is checked against the row, because the lookup is by run alone: a worker holds no tenant
        // identity, so the repository cannot scope for us. A run belonging to another organization must be
        // indistinguishable from one that does not exist.
        if (!owned.organizationId().equals(organizationId)) {
            throw ApiException.notFound();
        }
        TestRun run = owned.run();
        if (run.lifecycleState() != RunLifecycle.CLAIMED) {
            // Already stopping. If it is stopping *because somebody cancelled it*, this request is a duplicate of
            // that one and returning the run is honest. If it is stopping for any other reason, it is not: the
            // run will settle FAILED with no cancellation recorded anywhere, and answering "accepted, pending"
            // would tell the caller its cancellation is durable when nothing of the kind exists. That is the same
            // false-cause-in-an-audited-record this service refuses to write one state later.
            if (run.stopReason() == StopReason.USER_REQUESTED) {
                return Optional.of(run);
            }
            throw ApiException.conflict(
                    "RUN_NOT_CANCELLABLE", "This run is already stopping for a reason nobody requested.");
        }
        Instant at = terminalInstant(run, requestedAt);
        TestRun stopping = run.stopping(StopReason.USER_REQUESTED, requestedAt, at);
        leases.persistStop(
                organizationId,
                run,
                stopping,
                owned.attempt().fenced(at),
                UUID.randomUUID(),
                principal.principalId());
        LOGGER.atInfo()
                .addKeyValue("event", "RUN_STOP_REQUESTED")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("projectId", stopping.projectId())
                .addKeyValue("runId", runId)
                .addKeyValue("runVersion", stopping.runVersion())
                .log("Fenced an owned run's assignment at the tenant's request");
        return Optional.of(stopping);
    }

    /**
     * Ends one run whose queue deadline has passed. Losing the race to a cancellation or to another replica is
     * not an error: the loser observes an empty result and writes nothing.
     *
     * <p>READ COMMITTED is pinned for the same reason scheduling pins it — the locked row must be re-qualified
     * after the winner commits so the loser's predicate stops matching, and there is no retry here.
     *
     * @return the terminal run when this call is the one that ended it
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TestRun> expire(UUID organizationId, UUID runId) {
        if (organizationId == null || runId == null) {
            throw new IllegalArgumentException("A trusted organization and run are required.");
        }
        var locked = terminations.lockTerminable(organizationId, runId);
        if (locked.isEmpty() || locked.orElseThrow().lifecycleState() != RunLifecycle.QUEUED) {
            return Optional.empty();
        }
        TestRun previous = locked.orElseThrow();
        // Clamping to the deadline is what makes the database's own "completed_at >= queue_deadline_at" guard
        // unreachable by a clock skew rather than merely unlikely.
        TestRun expired = previous.expired(terminalInstant(previous, previous.queueDeadlineAt()));
        terminations.persistTermination(organizationId, previous, expired, UUID.randomUUID(), REAPER_ACTOR);
        terminated(organizationId, expired, TerminationReason.QUEUE_DEADLINE);
        return Optional.of(expired);
    }

    /**
     * A run this call could not lock has either already been decided or is in a phase early termination does not
     * reach. Which one it is has to be read back, and every answer stays scoped to the caller's organization so a
     * run belonging to another tenant is indistinguishable from one that never existed.
     */
    private TestRun alreadyDecided(UUID organizationId, UUID runId) {
        TestRun current = runs.findRun(organizationId, runId).orElseThrow(ApiException::notFound);
        if (current.terminationReason() == TerminationReason.USER_REQUESTED) {
            return current;
        }
        if (current.lifecycleState() == RunLifecycle.COMPLETED) {
            // The run is over, but not because anyone asked. Reporting it as cancelled would put a false cause in
            // an audited record, so the caller is told the outcome was already decided.
            throw ApiException.conflict(
                    "RUN_ALREADY_TERMINAL", "This run has already completed and cannot be cancelled.");
        }
        throw ApiException.conflict(
                "RUN_NOT_CANCELLABLE", "This run is in a phase that early cancellation cannot end.");
    }

    /**
     * The terminal instant is owned by the database clock, which is the same authority that stamped the run's
     * queue deadline. It is clamped so it can never precede the run's own last update or the event that caused
     * it, because creation and the HTTP request are stamped from the application clock and the two hosts drift.
     */
    private Instant terminalInstant(TestRun previous, Instant cause) {
        Instant floor = previous.updatedAt().isAfter(cause) ? previous.updatedAt() : cause;
        Instant databaseTime = terminations.currentDatabaseTime();
        return databaseTime.isBefore(floor) ? floor : databaseTime;
    }

    private void terminated(UUID organizationId, TestRun run, TerminationReason reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Observability must never decide whether a termination succeeds.
            return;
        }
        // Counted and logged only once the write is durable. A rolled-back transaction that had already counted
        // would report capacity being released that was never released.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Reason only. Organization, project, and run identity would be unbounded label cardinality.
                Counter.builder("kaas.run.terminated").tag("reason", reason.name()).register(meters).increment();
                LOGGER.atInfo()
                        .addKeyValue("event", "RUN_TERMINATED")
                        .addKeyValue("organizationId", organizationId)
                        .addKeyValue("projectId", run.projectId())
                        .addKeyValue("runId", run.runId())
                        .addKeyValue("runVersion", run.runVersion())
                        .addKeyValue("reason", reason.name())
                        .log("Ended a run before any worker owned it");
            }
        });
    }
}
