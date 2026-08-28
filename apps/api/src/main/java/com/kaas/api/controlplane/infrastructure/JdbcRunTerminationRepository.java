package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.RunTerminationRepository;
import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.TerminationReason;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.outbox.domain.TerminalDisposition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunTerminationRepository implements RunTerminationRepository {
    private static final int CANDIDATE_WINDOW = 40;

    private final JdbcTemplate jdbc;

    JdbcRunTerminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TestRun> lockTerminable(UUID organizationId, UUID runId) {
        return jdbc.query(
                        TestRunRowMapper.SELECT_COLUMNS
                                + """
                                  from test_runs
                                 where organization_id = ? and run_id = ?
                                   and lifecycle_state in ('CREATED', 'QUEUED')
                                   and cancellation_status = 'NOT_REQUESTED'
                                 for update
                                """,
                        TestRunRowMapper.INSTANCE,
                        organizationId,
                        runId)
                .stream()
                .findFirst();
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public List<SchedulableRun> findExpired(int batchSize) {
        // The deadline comparison uses the database clock, which is the same authority that stamped the deadline
        // when the run was queued. Comparing against an application clock would let host drift reap a run early.
        return jdbc.query(
                """
                with expired as (
                    -- Bounded candidate window first, so the planner can satisfy it from ix_test_runs_queue_deadline
                    -- alone. Without the ORDER BY and LIMIT it will not: queue_deadline_at's histogram is computed
                    -- over the whole table, where accumulating COMPLETED runs all have deadlines in the past, so
                    -- "<= now()" is estimated at most of the queue instead of a handful and a bitmap scan of every
                    -- QUEUED row wins on cost. That estimate degrades as terminal runs accumulate — the opposite of
                    -- what a partial index on the live queue is supposed to give.
                    select r.organization_id, r.project_id, r.run_id, r.run_version, r.queue_deadline_at
                      from test_runs r
                     where r.lifecycle_state = 'QUEUED' and r.queue_deadline_at <= now()
                     order by r.queue_deadline_at, r.run_id
                     limit ?
                ),
                eligible as (
                    select e.organization_id, e.project_id, e.run_id, e.run_version, e.queue_deadline_at,
                           row_number() over (
                               partition by e.organization_id
                               order by e.queue_deadline_at, e.run_id) as rank_in_org
                      from expired e
                      left join run_scheduling_control c on c.run_id = e.run_id
                      -- A run whose termination keeps failing must not be retried every tick, and one an
                      -- operator has quarantined is withheld until they clear its control row.
                     where c.run_id is null or (c.quarantined_at is null and c.next_attempt_at <= now())
                )
                select organization_id, project_id, run_id, run_version
                  from eligible
                 -- Round robin, for the same reason scheduling needs it: one organization with a large expired
                 -- backlog must not occupy the whole batch window and stall every other tenant's reaping.
                 order by rank_in_org, queue_deadline_at, run_id
                 limit ?
                """,
                SchedulableRunRowMapper.INSTANCE,
                // The candidate window is deliberately wider than the batch: round-robin fairness needs enough
                // rows to see more than one organization, and eligibility filtering may discard many of them.
                batchSize * CANDIDATE_WINDOW,
                batchSize);
    }

    @Override
    public void persistTermination(
            UUID organizationId, TestRun previous, TestRun terminal, UUID lifecycleEventId, String actor) {
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'COMPLETED', test_outcome = ?,
                       infrastructure_outcome = ?, termination_reason = ?, termination_phase = ?,
                       cancellation_status = ?, cancellation_requested_at = ?,
                       cancellation_acknowledged_at = ?, completed_at = ?, updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = ? and cancellation_status = 'NOT_REQUESTED' and run_version = ?
                """,
                terminal.runVersion(),
                terminal.testOutcome().name(),
                terminal.infrastructureOutcome().name(),
                terminal.terminationReason().name(),
                terminal.terminationPhase().name(),
                terminal.cancellationStatus().name(),
                timestamp(terminal.cancellationRequestedAt()),
                timestamp(terminal.cancellationAcknowledgedAt()),
                Timestamp.from(terminal.completedAt()),
                actor,
                Timestamp.from(terminal.updatedAt()),
                organizationId,
                terminal.projectId(),
                terminal.runId(),
                previous.lifecycleState().name(),
                previous.runVersion());
        if (changed != 1) {
            throw new IllegalStateException("The locked run did not satisfy the termination compare-and-set.");
        }
        // The attempt reference is read from the row rather than carried in, because the guard requires the event
        // to agree with the run's own current_attempt_id and a value passed through the application could differ.
        // A run terminated from CREATED has none, and the column is nullable for exactly that case.
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                select ?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', ?, 'COMPLETED', r.current_attempt_id, ?, ?
                  from test_runs r
                 where r.organization_id = ? and r.project_id = ? and r.run_id = ?
                """,
                lifecycleEventId,
                organizationId,
                terminal.projectId(),
                terminal.runId(),
                terminal.runVersion(),
                terminal.runVersion() - 1,
                previous.lifecycleState().name(),
                actor,
                Timestamp.from(terminal.completedAt()),
                organizationId,
                terminal.projectId(),
                terminal.runId());
        suppressPendingDispatch(organizationId, terminal, terminal.terminationReason());
        // Scheduling control state describes a run something still intends to act on. Leaving it behind would
        // point a foreign key at a terminal run and keep a quarantine visible for work that no longer exists.
        jdbc.update("delete from run_scheduling_control where run_id = ?", terminal.runId());
    }

    /**
     * Withdraws a dispatch no relay is currently holding, and clears any dead lease it left behind.
     *
     * <p>The predicate mirrors the relay's own claim predicate rather than being stricter than it. The relay
     * reclaims a row whose lease has expired, so a row abandoned by a crashed relay is publishable; refusing to
     * suppress it would leave a dispatch that cancellation cannot withdraw and a later relay pass then delivers,
     * for the first time, on behalf of a run that is already over. A row in retry backoff is likewise suppressible
     * — "suppressed" means withdrawn before publication, not never attempted — and its attempt history is kept.
     *
     * <p>A live lease is deliberately left alone. That message may already be at the broker, and suppressing it
     * would be pretending the control plane can recall something it cannot. It publishes and becomes stale, which
     * is a duplicate-delivery case a consumer has to reject on its own terms in any event.
     */
    private void suppressPendingDispatch(UUID organizationId, TestRun terminal, TerminationReason reason) {
        jdbc.update(
                """
                update outbox_messages
                   set terminal_disposition = ?, relay_claim_id = null, relay_claimed_at = null,
                       relay_claim_expires_at = null
                 where organization_id = ? and project_id = ? and run_id = ?
                   and published_at is null and terminal_disposition is null
                   and (relay_claim_id is null or relay_claim_expires_at <= now())
                """,
                suppression(reason).name(), organizationId, terminal.projectId(), terminal.runId());
    }

    /**
     * The outbox owns this vocabulary, so it is imported rather than spelled out again. The alternative — a
     * literal, to avoid a compile-time reference to the delivery context — was a third copy of a string the
     * database and the enum already hold, and renaming the constant would have compiled, migrated, and passed the
     * whole suite while the enum quietly became decorative. An ArchUnit rule keeps the dependency one-way and
     * confined to this adapter, which already writes outbox rows directly.
     */
    private static TerminalDisposition suppression(TerminationReason reason) {
        return switch (reason) {
            case USER_REQUESTED -> TerminalDisposition.SUPPRESSED_CANCELLED;
            case QUEUE_DEADLINE -> TerminalDisposition.SUPPRESSED_QUEUE_TIMEOUT;
        };
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
