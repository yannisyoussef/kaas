package com.kaas.api.execution.infrastructure;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import com.kaas.api.controlplane.domain.TestOutcome;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.controlplane.infrastructure.ExecutionAttemptRowMapper;
import com.kaas.api.controlplane.infrastructure.TestRunRowMapper;
import com.kaas.api.execution.application.ExecutionLifecycleRepository;
import com.kaas.api.execution.application.ExecutionPhaseService;
import com.kaas.api.execution.domain.ExecutionResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExecutionLifecycleRepository implements ExecutionLifecycleRepository {

    /** The guard admits a deadline stop only from this actor, so the constant is shared with it by name. */
    public static final String EXECUTION_RECONCILER_ACTOR = "kaas.execution-reconciler";

    private final JdbcTemplate jdbc;

    public JdbcExecutionLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AssignedRun> lockAssignedRun(UUID runId) {
        Optional<UUID> owner = jdbc
                .query(
                        "select organization_id from test_runs where run_id = ? for update",
                        (rs, row) -> rs.getObject("organization_id", UUID.class),
                        runId)
                .stream()
                .findFirst();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        UUID organizationId = owner.orElseThrow();
        Optional<TestRun> run = jdbc
                .query(
                        TestRunRowMapper.SELECT_COLUMNS
                                + """
                                  from test_runs
                                 where organization_id = ? and run_id = ?
                                """,
                        TestRunRowMapper.INSTANCE,
                        organizationId,
                        runId)
                .stream()
                .findFirst();
        if (run.isEmpty()) {
            return Optional.empty();
        }
        // Joined through the run's own current_attempt_id rather than by run alone. An attempt the run has
        // moved on from is then not returned at all, which makes "this is still the run's attempt" a property
        // of the query rather than a comparison somebody has to remember to write in the service.
        return jdbc
                .query(
                        ExecutionAttemptRowMapper.SELECT_COLUMNS
                                + """
                                  from execution_attempts a
                                 where a.organization_id = ? and a.project_id = ? and a.run_id = ?
                                   and a.attempt_id = (select r.current_attempt_id from test_runs r
                                                        where r.organization_id = a.organization_id
                                                          and r.run_id = a.run_id)
                                 for update
                                """,
                        ExecutionAttemptRowMapper.INSTANCE,
                        organizationId,
                        run.orElseThrow().projectId(),
                        runId)
                .stream()
                .findFirst()
                .map(attempt -> new AssignedRun(organizationId, run.orElseThrow(), attempt));
    }

    @Override
    public Instant currentDatabaseTime() {
        // clock_timestamp(), not now(): now() is the transaction's start instant, so every deadline armed in a
        // long transaction would be measured from before the work that preceded it.
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void persistPhase(
            UUID organizationId,
            TestRun previous,
            TestRun advanced,
            ExecutionAttempt attempt,
            UUID lifecycleEventId,
            String sandboxReference) {

        // The worker id IS the actor. It already lives in the kaas.worker. namespace the guard requires, so
        // prefixing it again produced 'kaas.worker.kaas.worker.local' — which still satisfies the LIKE and so
        // would never have failed, while every audit row named a worker that does not exist.
        String actor = attempt.assignment().workerId();
        // Compare-and-set on the state AND the version. The lock serialises writers; the predicate is what
        // makes a lost update impossible if the lock is ever relaxed, and it is what makes the failure loud
        // rather than silent.
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = ?, phase_deadline_at = ?, execution_started_at = ?,
                       updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = ? and cancellation_status = 'NOT_REQUESTED' and stop_reason is null
                   and run_version = ? and current_attempt_id = ?
                """,
                advanced.runVersion(),
                advanced.lifecycleState().name(),
                Timestamp.from(advanced.phaseDeadlineAt()),
                advanced.executionStartedAt() == null ? null : Timestamp.from(advanced.executionStartedAt()),
                actor,
                Timestamp.from(advanced.updatedAt()),
                organizationId,
                advanced.projectId(),
                advanced.runId(),
                previous.lifecycleState().name(),
                previous.runVersion(),
                attempt.attemptId());
        if (changed != 1) {
            throw new IllegalStateException("The locked run did not satisfy the phase compare-and-set.");
        }

        // The attempt's own execution history. Each phase stamps exactly the instant it is the authority for,
        // so no transition overwrites a fact an earlier one established.
        switch (advanced.lifecycleState()) {
            case PROVISIONING -> jdbc.update(
                    """
                    update execution_attempts
                       set provisioned_at = ?, sandbox_reference = ?
                     where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                    """,
                    Timestamp.from(advanced.updatedAt()),
                    sandboxReference,
                    organizationId, advanced.projectId(), advanced.runId(), attempt.attemptId());
            case RUNNING -> jdbc.update(
                    """
                    update execution_attempts
                       set execution_started_at = ?
                     where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                    """,
                    Timestamp.from(advanced.updatedAt()),
                    organizationId, advanced.projectId(), advanced.runId(), attempt.attemptId());
            case COLLECTING_RESULTS -> jdbc.update(
                    """
                    update execution_attempts
                       set execution_finished_at = ?
                     where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                    """,
                    Timestamp.from(advanced.updatedAt()),
                    organizationId, advanced.projectId(), advanced.runId(), attempt.attemptId());
            // PROCESSING_RESULTS is control-plane work after the sandbox is gone, so it stamps nothing on the
            // attempt. Enumerated rather than defaulted so that adding a phase forces a decision here.
            case PROCESSING_RESULTS -> { }
            default -> throw new IllegalStateException(
                    "Not an execution phase: " + advanced.lifecycleState());
        }

        insertLifecycleEvent(
                organizationId, advanced, previous.lifecycleState(), attempt.attemptId(), actor,
                lifecycleEventId);
    }

    @Override
    public void persistResultAndComplete(
            UUID organizationId,
            TestRun previous,
            TestRun completed,
            ExecutionAttempt attempt,
            ExecutionResult result,
            UUID lifecycleEventId) {

        // The worker id IS the actor. It already lives in the kaas.worker. namespace the guard requires, so
        // prefixing it again produced 'kaas.worker.kaas.worker.local' — which still satisfies the LIKE and so
        // would never have failed, while every audit row named a worker that does not exist.
        String actor = attempt.assignment().workerId();

        // The evidence is written FIRST. The run's own trigger refuses a COMPLETED run carrying
        // EXECUTION_COMPLETED without its matching result, so writing the run first would fail on a constraint
        // whose message describes the symptom. In this order the constraint is a backstop rather than the
        // control flow.
        jdbc.update(
                """
                insert into execution_results
                    (result_id, organization_id, project_id, run_id, attempt_id, assignment_epoch, command_id,
                     run_snapshot_sha256, result_digest, test_outcome, infrastructure_outcome, document,
                     submitted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                result.resultId(),
                result.organizationId(),
                result.projectId(),
                result.runId(),
                result.attemptId(),
                result.assignmentEpoch(),
                result.commandId(),
                result.runSnapshotSha256(),
                result.resultDigest(),
                result.testOutcome().name(),
                result.infrastructureOutcome().name(),
                result.document(),
                Timestamp.from(result.submittedAt()));

        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'COMPLETED', phase_deadline_at = null,
                       test_outcome = ?, infrastructure_outcome = 'SUCCEEDED',
                       termination_reason = 'EXECUTION_COMPLETED', termination_phase = 'EXECUTION',
                       completed_at = ?, updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = 'PROCESSING_RESULTS' and cancellation_status = 'NOT_REQUESTED'
                   and stop_reason is null and run_version = ? and current_attempt_id = ?
                """,
                completed.runVersion(),
                completed.testOutcome().name(),
                Timestamp.from(completed.completedAt()),
                actor,
                Timestamp.from(completed.updatedAt()),
                organizationId,
                completed.projectId(),
                completed.runId(),
                previous.runVersion(),
                attempt.attemptId());
        if (changed != 1) {
            throw new IllegalStateException("The locked run did not satisfy the completion compare-and-set.");
        }

        // Two statements, and they have to be two.
        //
        // The attempt guard keeps execution history and the assignment strictly apart: a history write may not
        // touch the assignment, and an assignment transition may not touch history. That separation is what
        // stops a worker renewing its own lease while reporting progress, and the price of it is that ending an
        // attempt takes one statement for what it did and one for the fact that it no longer owns anything.
        jdbc.update(
                """
                update execution_attempts
                   set infrastructure_disposition = 'SUCCEEDED'
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                """,
                organizationId, completed.projectId(), completed.runId(), attempt.attemptId());

        // The assignment ends with the run. A COMPLETED run holding a live assignment is refused at commit by
        // the scheduling-bundle constraint, and rightly so: the worker owns nothing once the run is over, and
        // leaving the lease live would let a reconciler believe there was still work in flight.
        jdbc.update(
                """
                update execution_attempts
                   set attempt_state = 'FENCED', fenced_at = clock_timestamp()
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and attempt_state = 'CLAIMED' and fenced_at is null
                """,
                organizationId, completed.projectId(), completed.runId(), attempt.attemptId());

        insertLifecycleEvent(
                organizationId, completed, RunLifecycle.PROCESSING_RESULTS, attempt.attemptId(), actor,
                lifecycleEventId);
    }

    @Override
    public void stopForInfrastructureFailure(
            UUID organizationId, TestRun run, ExecutionAttempt attempt, String workerId, Instant at) {

        // Clamped up, like every other application-sourced instant that meets this row.
        Instant stoppedAt = at.isBefore(run.updatedAt()) ? run.updatedAt() : at;
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = run_version + 1, lifecycle_state = 'STOPPING',
                       stop_reason = 'INFRASTRUCTURE_FAILURE', phase_deadline_at = null,
                       updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = ? and run_version = ?
                   and cancellation_status = 'NOT_REQUESTED' and stop_reason is null
                """,
                workerId,
                Timestamp.from(stoppedAt),
                organizationId,
                run.projectId(),
                run.runId(),
                run.lifecycleState().name(),
                run.runVersion());
        if (changed != 1) {
            throw new IllegalStateException("The locked run did not satisfy the infrastructure-failure stop.");
        }

        // The attempt records what happened to it, then ends. Two statements because the attempt guard keeps
        // execution history and the assignment strictly apart.
        jdbc.update(
                """
                update execution_attempts
                   set infrastructure_disposition = 'FAILED'
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and infrastructure_disposition is null
                """,
                organizationId, run.projectId(), run.runId(), attempt.attemptId());
        jdbc.update(
                """
                update execution_attempts
                   set attempt_state = 'FENCED', fenced_at = ?
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and attempt_state = 'CLAIMED' and fenced_at is null
                """,
                Timestamp.from(stoppedAt),
                organizationId, run.projectId(), run.runId(), attempt.attemptId());

        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                values (?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', ?, 'STOPPING', ?, ?, ?)
                """,
                UUID.randomUUID(),
                organizationId,
                run.projectId(),
                run.runId(),
                run.runVersion() + 1,
                run.runVersion(),
                run.lifecycleState().name(),
                attempt.attemptId(),
                workerId,
                Timestamp.from(stoppedAt));
    }

    @Override
    public Optional<ExecutionResult> findResult(UUID organizationId, UUID attemptId, int assignmentEpoch) {
        return jdbc
                .query(
                        """
                        select result_id, organization_id, project_id, run_id, attempt_id, assignment_epoch,
                               command_id, run_snapshot_sha256, result_digest, test_outcome,
                               infrastructure_outcome, document::text as document, submitted_at
                          from execution_results
                         where organization_id = ? and attempt_id = ? and assignment_epoch = ?
                        """,
                        (rs, row) -> new ExecutionResult(
                                rs.getObject("result_id", UUID.class),
                                rs.getObject("organization_id", UUID.class),
                                rs.getObject("project_id", UUID.class),
                                rs.getObject("run_id", UUID.class),
                                rs.getObject("attempt_id", UUID.class),
                                rs.getInt("assignment_epoch"),
                                rs.getObject("command_id", UUID.class),
                                rs.getString("run_snapshot_sha256"),
                                rs.getString("result_digest"),
                                TestOutcome.valueOf(rs.getString("test_outcome")),
                                InfrastructureOutcome.valueOf(rs.getString("infrastructure_outcome")),
                                rs.getString("document"),
                                rs.getTimestamp("submitted_at").toInstant()),
                        organizationId,
                        attemptId,
                        assignmentEpoch)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuthorizedCommand> findAuthorizedCommand(
            UUID organizationId, UUID attemptId, int assignmentEpoch) {
        // Scoped by attempt AND epoch, not by attempt alone. A command issued to a previous assignment of the
        // same attempt is a command this caller was never given, and matching on the attempt would accept it.
        return jdbc
                .query(
                        """
                        -- The run version lives in the command document rather than in a column of its own.
                        -- Read from there rather than duplicated into the table: the document is what was
                        -- digested and delivered, so it is the authority on what the worker was told, and a
                        -- second copy could disagree with it.
                        select command_id, (document->>'runVersion')::bigint as run_version
                          from execution_commands
                         where organization_id = ? and attempt_id = ? and assignment_epoch = ?
                        """,
                        (rs, row) -> new AuthorizedCommand(
                                rs.getObject("command_id", UUID.class), rs.getLong("run_version")),
                        organizationId,
                        attemptId,
                        assignmentEpoch)
                .stream()
                .findFirst();
    }

    @Override
    public List<OverdueRun> findOverdue(int limit) {
        // Compared against the database's own clock, in the database. Comparing against an application instant
        // would make expiry depend on the reconciler host's clock agreeing with the one that armed the
        // deadline, and ordinary container drift is enough to break that.
        return jdbc.query(
                """
                select organization_id, run_id, lifecycle_state, phase_deadline_at
                  from test_runs
                 where lifecycle_state in ('PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS', 'PROCESSING_RESULTS')
                   and phase_deadline_at <= clock_timestamp()
                 order by phase_deadline_at
                 limit ?
                """,
                (rs, row) -> new OverdueRun(
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        RunLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getTimestamp("phase_deadline_at").toInstant()),
                limit);
    }

    @Override
    public boolean stopOverdue(
            UUID organizationId, UUID runId, RunLifecycle expected, StopReason reason) {

        // One statement, and every condition it depends on is inside it. The phase must still be the one the
        // scan found, the deadline must still be in the past, and both are evaluated against clock_timestamp()
        // in the database rather than against an instant this process computed — the deadline was armed by that
        // clock, and comparing it to another one makes expiry depend on two hosts agreeing.
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = run_version + 1, lifecycle_state = 'STOPPING', stop_reason = ?,
                       phase_deadline_at = null, updated_by = ?, updated_at = clock_timestamp()
                 where organization_id = ? and run_id = ?
                   and lifecycle_state = ? and phase_deadline_at <= clock_timestamp()
                   and stop_reason is null
                """,
                reason.name(),
                EXECUTION_RECONCILER_ACTOR,
                organizationId,
                runId,
                expected.name());
        if (changed != 1) {
            return false;
        }

        // The assignment ends with the phase. A STOPPING run holding a live assignment is refused at commit by
        // the scheduling-bundle constraint, and that constraint is right: an unfenced worker would go on
        // heartbeating a run the platform has already given up on, and every reconciler looking at the fleet
        // would count it as work still in flight.
        //
        // Scoped to the run's CURRENT attempt, not to every attempt on the run. Equivalent today — one attempt
        // per run — and wrong the moment infrastructure retry lands, when a deadline stop selected against
        // attempt N would also fence attempt N+1, including one just reassigned to a healthy worker.
        //
        // Not required to affect a row. The lease reconciler may have fenced this attempt already — the two run
        // on independent schedules and can both decide the same worker is gone — and arriving second is a race
        // that resolves correctly, not a failure.
        jdbc.update(
                """
                update execution_attempts
                   set attempt_state = 'FENCED', fenced_at = clock_timestamp()
                 where organization_id = ? and run_id = ?
                   and attempt_id = (select r.current_attempt_id from test_runs r
                                      where r.organization_id = ? and r.run_id = ?)
                   and attempt_state = 'CLAIMED' and fenced_at is null
                """,
                organizationId, runId, organizationId, runId);

        // Selected from the row rather than reconstructed here. The version and instant the event must agree
        // with are facts the database has already settled, and recomputing them would be a second opinion.
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                select ?, r.organization_id, r.project_id, r.run_id, r.run_version, r.run_version - 1,
                       'RUN_STATE_CHANGED', ?, 'STOPPING', r.current_attempt_id, ?, r.updated_at
                  from test_runs r
                 where r.organization_id = ? and r.run_id = ?
                """,
                UUID.randomUUID(),
                expected.name(),
                EXECUTION_RECONCILER_ACTOR,
                organizationId,
                runId);
        return true;
    }

    private void insertLifecycleEvent(
            UUID organizationId, TestRun run, RunLifecycle previousState, UUID attemptId, String actor,
            UUID eventId) {
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                values (?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', ?, ?, ?, ?, ?)
                """,
                // The caller's identifier, not one invented here. The sibling repositories use theirs, and an
                // ignored parameter is the only handle a replayed transaction would have had to be idempotent
                // on event_id.
                eventId,
                organizationId,
                run.projectId(),
                run.runId(),
                run.runVersion(),
                run.runVersion() - 1,
                previousState.name(),
                run.lifecycleState().name(),
                attemptId,
                actor,
                Timestamp.from(run.updatedAt()));
    }

}
