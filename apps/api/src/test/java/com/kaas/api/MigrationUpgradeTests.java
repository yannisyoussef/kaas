package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A fresh-database migration test is not a migration test.
 *
 * <p>The outbox relay slice shipped a migration whose backfill was rejected by the previous version's own trigger.
 * It applied cleanly to an empty database — which is what every Testcontainers suite and CI run does — and would
 * have failed the first time it met production data, because a backfill over zero rows cannot trip a guard,
 * violate a constraint, or leave a NULL behind.
 *
 * <p>So this class runs both directions and is a permanent regression gate:
 *
 * <ul>
 *   <li>every migration applies to an empty database, and
 *   <li>the <em>most recent</em> migration applies to a database already carrying representative rows from the
 *       version before it.
 * </ul>
 *
 * <p>That second bullet is narrower than it sounds and the wording is deliberate. The baseline is the
 * second-newest version on disk, so each migration gets populated coverage exactly once — in the slice that
 * introduces it — and loses it the moment the next migration lands. A slice that ships two migrations therefore
 * leaves the earlier one tested against an empty database only. That is a known limit of this gate, not an
 * oversight, and it is the reason the populated assertions have to be written while the migration is the newest
 * one.
 *
 * <p>It deliberately uses Flyway and JDBC directly rather than a Spring context: the subject is the migration
 * chain itself, and the baseline version has to be chosen before any application bean could start.
 */
@Testcontainers
class MigrationUpgradeTests {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-migrations");

    @Test
    void everyMigrationAppliesToAnEmptyDatabase() throws Exception {
        String database = freshDatabase("fresh");
        Flyway.configure().dataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();

        assertThat(appliedVersions(database)).isEqualTo(versionsOnDisk());
        // Nothing may remain pending, and no checksum may have drifted from what is on disk.
        assertThat(Flyway.configure().dataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()).load().info().pending()).isEmpty();
    }

    @Test
    void everyMigrationAppliesToAPopulatedDatabaseFromThePreviousVersion() throws Exception {
        List<String> versions = versionsOnDisk();
        String baseline = versions.get(versions.size() - 2);
        String database = freshDatabase("upgrade");

        // Stop one version short, then fill the database with rows a real deployment would already hold.
        Flyway.configure().dataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()).target(baseline).load().migrate();
        assertThat(appliedVersions(database)).isEqualTo(versions.subList(0, versions.size() - 1));
        seedRepresentativeRows(database);
        assertTheFixtureReachesWhatTheUpgradeChanges(database);

        // The newest migration now runs against real rows, with every runtime trigger of the previous version
        // still installed. A migration that transforms data must cope with the guards that were protecting it.
        Flyway.configure().dataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();

        assertThat(appliedVersions(database)).isEqualTo(versions);
        assertRepresentativeRowsSurvived(database);
    }

    /**
     * Proves, before the upgrade runs, that the fixture actually holds rows the pending migrations will act on.
     *
     * <p>This is the same rule as for a backfill, applied to the other way a migration touches existing data. A
     * migration that adds a validating CHECK, drops a NOT NULL, or changes a column type is checked against
     * every row already in the table — so over an empty table it proves exactly nothing, and would pass while
     * shipping a constraint production data violates.
     *
     * <p>Replacing a guard <em>function</em> is deliberately not on that list. {@code CREATE OR REPLACE FUNCTION}
     * validates nothing against existing rows: a plpgsql body is not executed until the next write, so a broken
     * guard is caught by the runtime suites rather than here. Only validating DDL is covered by this gate.
     *
     * <p>Asserting these is the point: if a future fixture change empties one of them, this fails loudly instead
     * of going quietly green.
     */
    private void assertTheFixtureReachesWhatTheUpgradeChanges(String database) throws Exception {
        // V7 adds validating CHECKs to test_runs, so the table must be non-empty and must already hold the
        // states those CHECKs reason about.
        assertThat(count(database, "test_runs where lifecycle_state = 'CREATED'")).isPositive();
        assertThat(count(database, "test_runs where lifecycle_state = 'QUEUED'")).isPositive();
        assertThat(count(database, "test_runs where cancellation_status = 'NOT_REQUESTED'")).isPositive();
        // It relaxes run_lifecycle_events.attempt_id and replaces that table's transition CHECK, which is
        // validated against every existing event.
        assertThat(count(database, "run_lifecycle_events where attempt_id is not null")).isPositive();
        assertThat(count(database, "run_lifecycle_events where run_version <> sequence + 1")).isZero();
        // And it rewrites the outbox accounting CHECKs, which are validated against every delivery state.
        assertThat(count(database, "outbox_messages where terminal_disposition is not null")).isPositive();
        assertThat(count(database, "outbox_messages where published_at is not null")).isPositive();
        assertThat(count(database, "outbox_messages where published_at is null"
                        + " and terminal_disposition is null")).isPositive();
        // V8 adds validating CHECKs to execution_attempts, evaluated against every attempt already present.
        assertThat(count(database, "execution_attempts where attempt_state = 'WAITING_FOR_CLAIM'")).isPositive();

        // V8 also DROPS and re-ADDS the two termination CHECKs and the lifecycle-event transition CHECK. Those
        // are the constraints whose entire subject is a terminated run, so a fixture without one validates them
        // against nothing: every disjunct short-circuits on `termination_reason IS NULL`, and a dropped branch
        // ships green here and fails on the first real database. Each reason and each event shape the previous
        // version can produce has to be present *before* the upgrade runs.
        assertThat(count(database, "test_runs where termination_reason = 'USER_REQUESTED'")).isPositive();
        assertThat(count(database, "test_runs where termination_reason = 'QUEUE_DEADLINE'")).isPositive();
        assertThat(count(database, "test_runs where lifecycle_state = 'COMPLETED'")).isPositive();
        assertThat(count(database, "run_lifecycle_events where previous_state = 'CREATED'"
                        + " and lifecycle_state = 'QUEUED' and sequence = 1")).isPositive();
        assertThat(count(database, "run_lifecycle_events where previous_state = 'CREATED'"
                        + " and lifecycle_state = 'COMPLETED' and attempt_id is null")).isPositive();
        assertThat(count(database, "run_lifecycle_events where previous_state = 'QUEUED'"
                        + " and lifecycle_state = 'COMPLETED' and attempt_id is not null")).isPositive();

        // V12 DROPS and re-ADDS ck_execution_capabilities_type, and PostgreSQL validates a re-added CHECK
        // against every row already in the table. Both existing values have to be present BEFORE the upgrade
        // runs, or the widened constraint is checked against nothing and a version of it that accidentally
        // dropped 'SOURCE' or 'SECRET' would apply cleanly here and reject real rows on first deployment.
        assertThat(count(database, "execution_capabilities where capability_type = 'SOURCE'")).isPositive();
        assertThat(count(database, "execution_capabilities where capability_type = 'SECRET'")).isPositive();
        // V12 also adds ck_execution_capabilities_egress_is_not_redeemed, which is likewise validated against
        // every existing row. Its subject is the redemption counter, so a fixture whose capabilities have all
        // been redeemed zero times leaves the interesting half of the constraint short-circuited — a redeemed
        // row is what proves the exemption is scoped to EGRESS and does not accidentally forbid redemption
        // everywhere.
        assertThat(count(database, "execution_capabilities where redemption_count > 0")).isPositive();

        // V12 adds NOT NULL columns with defaults and foreign keys to projects and run_snapshots. Both are
        // validated against every existing row, so both tables have to be non-empty here or the new
        // references are checked against nothing.
        assertThat(count(database, "projects")).isPositive();
        assertThat(count(database, "run_snapshots")).isPositive();
        // And it adds two CHECKs to network_policy_revisions, validated against the seeded DENY_ALL row.
        assertThat(count(database, "network_policy_revisions")).isPositive();

        // V10 DOES revalidate against existing rows, and this is what the fixture has to satisfy for it.
        //
        // It drops and re-adds four constraints that are then checked against every row already present: the
        // lifecycle-event transition CHECK, both termination CHECKs, and the stop-reason CHECK. A fixture
        // without a terminated run validates all of them vacuously — every disjunct short-circuits on
        // `termination_reason IS NULL` — so a dropped arm would ship green here and fail on the first real
        // database. Each reason and each event shape the previous version could produce has to be present
        // BEFORE the upgrade runs.
        assertThat(count(database, "test_runs where termination_reason = 'USER_REQUESTED'"
                        + " and termination_phase = 'CANCELLATION' and infrastructure_outcome = 'CANCELLED'"))
                .isPositive();
        assertThat(count(database, "test_runs where termination_reason = 'QUEUE_DEADLINE'"
                        + " and termination_phase = 'QUEUE' and infrastructure_outcome = 'TIMED_OUT'"))
                .isPositive();
        assertThat(count(database, "test_runs where stop_reason is not null")).isPositive();
        // V10 also adds ck_test_runs_outcomes_orthogonal, which is validated against every pre-existing
        // terminal run. Those runs have a non-null infrastructure outcome and NOT_AVAILABLE as their test
        // outcome, which is exactly the shape the new constraint has to accept.
        assertThat(count(database, "test_runs where infrastructure_outcome is not null"
                        + " and test_outcome = 'NOT_AVAILABLE'"))
                .isPositive();
        // And the rewritten transition CHECK is validated against every event. All four edge shapes the
        // previous version could produce must be present, because the rewrite decoupled the sequence from the
        // edge and a mistake there would only show on a row that exercises it.
        assertThat(count(database, "run_lifecycle_events where previous_state = 'CREATED'"
                        + " and lifecycle_state = 'QUEUED'")).isPositive();
        assertThat(count(database, "run_lifecycle_events where previous_state = 'CREATED'"
                        + " and lifecycle_state = 'COMPLETED'")).isPositive();
        assertThat(count(database, "run_lifecycle_events where previous_state = 'QUEUED'"
                        + " and lifecycle_state = 'COMPLETED'")).isPositive();
        assertThat(count(database, "run_lifecycle_events where run_version <> sequence + 1")).isZero();

        // V9 is where this method has nothing to demand, and saying so is the honest form of the check rather
        // than an omission. It creates five new tables, seeds one platform-owned policy revision, and adds
        // triggers only to tables it created in the same file. No statement in it transforms an existing row,
        // no constraint it adds is validated against pre-existing data, and no foreign key it declares points
        // from an old table to a new one. There is therefore no predicate a fixture row could satisfy — and
        // seeding rows to "cover" V9 would be seeding rows nothing evaluates, which is the exact
        // populated-but-unexercised fixture this method exists to prevent.
        //
        // What V9 *is* checked for lives in assertRepresentativeRowsSurvived below: that it invented no
        // authority, and that its one seeded row is the one the migration wrote.
        assertThat(count(database, "execution_attempts")).isPositive();
    }

    /** Everything the upgrade must not lose, damage, or silently rewrite. */
    private void assertRepresentativeRowsSurvived(String database) throws Exception {
        assertThat(count(database, "projects")).isEqualTo(1);
        assertThat(count(database, "feature_revisions")).isEqualTo(1);
        assertThat(count(database, "environment_revisions")).isEqualTo(1);
        assertThat(count(database, "run_profile_revisions")).isEqualTo(1);
        assertThat(count(database, "run_snapshots")).isEqualTo(10);
        // One CREATED run and one QUEUED run, with the QUEUED run's full scheduling bundle intact.
        assertThat(count(database, "test_runs where lifecycle_state = 'CREATED'")).isEqualTo(1);
        assertThat(count(database, "test_runs where lifecycle_state = 'QUEUED'")).isEqualTo(4);
        assertThat(count(database, "test_runs where lifecycle_state = 'COMPLETED'")).isEqualTo(4);
        // Four queued bundles, the two attempts belonging to runs terminated out of QUEUED, and the fenced
        // attempts under the stopping run and the settled lease-lost run.
        assertThat(count(database, "execution_attempts")).isEqualTo(8);
        assertThat(count(database, "test_runs where lifecycle_state = 'STOPPING'")).isEqualTo(1);
        assertThat(count(database, "execution_attempts where attempt_state = 'FENCED'")).isEqualTo(2);
        assertThat(count(database, "execution_dispatches")).isEqualTo(4);
        // Four scheduling events, two more for the runs terminated out of QUEUED, three terminal events, and
        // the seven that give the stopping and settled runs the histories a real run of their version has.
        assertThat(count(database, "run_lifecycle_events")).isEqualTo(16);

        // EVERY EDGE SHAPE THE PREVIOUS VERSION COULD PRODUCE IS PRESENT BEFORE THE UPGRADE.
        //
        // V10 rewrites ck_run_lifecycle_events_transition and decouples sequence from edge — exactly the change
        // where a dropped arm hides. Three of the six edges were seeded by nothing, so a rewrite that silently
        // dropped QUEUED->CLAIMED, CLAIMED->STOPPING, or STOPPING->COMPLETED would have passed this gate and
        // then refused real production rows on first deployment.
        for (String edge : java.util.List.of(
                "'CREATED','QUEUED'", "'CREATED','COMPLETED'", "'QUEUED','COMPLETED'",
                "'QUEUED','CLAIMED'", "'CLAIMED','STOPPING'", "'STOPPING','COMPLETED'")) {
            assertThat(count(database,
                            "run_lifecycle_events where (previous_state, lifecycle_state) = (" + edge + ")"))
                    .as("edge %s must be present before the upgrade, or its guard arm is validated against nothing", edge)
                    .isGreaterThan(0);
        }
        // And both stop reasons the previous version could produce, including the one that makes
        // ck_test_runs_stop_reason_agrees actually evaluate rather than short-circuit.
        assertThat(count(database, "test_runs where stop_reason = 'LEASE_LOST'")).isEqualTo(2);
        assertThat(count(database,
                        "test_runs where stop_reason is not null and termination_reason = stop_reason"))
                .as("ck_test_runs_stop_reason_agrees is only exercised by a row where both are set")
                .isEqualTo(1);

        // Every delivery state the outbox can hold survives, still carrying its payload and digest.
        assertThat(count(database, "outbox_messages")).isEqualTo(5);
        // Four of them are dispatch-backed EXECUTION_DISPATCH rows: the shape a joined or filtered backfill has
        // to select. If this drops to zero the gate silently stops testing anything.
        assertThat(count(database, "outbox_messages where message_type = 'EXECUTION_DISPATCH'"
                        + " and dispatch_id is not null"))
                .isEqualTo(4);
        assertThat(count(database, "outbox_messages o join execution_dispatches d"
                        + " on d.dispatch_id = o.dispatch_id and d.payload_sha256 = o.payload_sha256"))
                .isEqualTo(4);
        assertThat(count(database, "outbox_messages where published_at is null and terminal_disposition is null"
                        + " and available_at <= now()"))
                .isEqualTo(2);
        assertThat(count(database, "outbox_messages where published_at is not null")).isEqualTo(1);
        assertThat(count(database, "outbox_messages where published_at is null and terminal_disposition is null"
                        + " and available_at > now()"))
                .isEqualTo(1);
        assertThat(count(database, "outbox_messages where terminal_disposition = 'RETRIES_EXHAUSTED'"))
                .isEqualTo(1);
        assertThat(count(database, "outbox_messages where message_type = 'RUN_STATE_CHANGED'")).isEqualTo(1);
        assertThat(count(database, "outbox_messages where payload is null or payload_sha256 is null")).isZero();

        // The new structures exist and start empty: absence of a control row means immediately eligible, so no
        // backfill is required and none may have been invented.
        assertThat(count(database, "run_scheduling_control")).isZero();

        // V7 adds terminal state to a table that already had rows. It transforms nothing, and the assertion that
        // it transformed nothing is the point: every pre-existing run must still be unfinished, with no invented
        // completion time, reason, or cancellation.
        assertThat(count(database, "test_runs where lifecycle_state <> 'COMPLETED'"
                        + " and (completed_at is not null or termination_reason is not null"
                        + " or termination_phase is not null or cancellation_requested_at is not null"
                        + " or cancellation_acknowledged_at is not null)"))
                .isZero();
        assertThat(count(database, "test_runs where lifecycle_state <> 'COMPLETED'")).isEqualTo(6);
        // And the terminated ones kept the reasons they were terminated for.
        assertThat(count(database, "test_runs where termination_reason = 'QUEUE_DEADLINE'"
                        + " and infrastructure_outcome = 'TIMED_OUT'")).isEqualTo(1);
        assertThat(count(database, "test_runs where termination_reason = 'USER_REQUESTED'"
                        + " and infrastructure_outcome = 'CANCELLED'")).isEqualTo(2);
        // The lifecycle events kept their attempts; relaxing the column did not blank anything.
        // The only event without an attempt is the CREATED->COMPLETED one, which never had one.
        assertThat(count(database, "run_lifecycle_events where attempt_id is null")).isEqualTo(1);
        // And no delivery state was reinterpreted as a suppression.
        assertThat(count(database, "outbox_messages where terminal_disposition like 'SUPPRESSED%'")).isZero();
        assertThat(count(database, "outbox_messages where terminal_disposition = 'RETRIES_EXHAUSTED'"))
                .isEqualTo(1);
        // The reaper's index exists, because a queue deadline nothing can select efficiently is a promise the
        // platform cannot keep at scale.
        assertThat(count(database, "pg_indexes where indexname = 'ix_test_runs_queue_deadline'")).isEqualTo(1);

        // V8 likewise transforms nothing. No pre-existing run acquires a stop reason, and no pre-existing
        // attempt acquires an assignment — an upgrade that invented either would be handing out ownership of
        // work to a worker that never claimed it.
        // Two runs carry a stop reason — one still STOPPING, one already settled — seeded so V10's rewrite of
        // that constraint is validated against real values in both of the shapes V8 could produce. What must
        // not happen is a PRE-EXISTING run acquiring one it never had.
        assertThat(count(database, "test_runs where stop_reason is not null")).isEqualTo(2);
        assertThat(count(database, "test_runs where stop_reason is not null"
                        + " and lifecycle_state not in ('STOPPING', 'COMPLETED')")).isZero();
        // Exactly one attempt carries an assignment: the fenced one seeded under the stopping run. What must
        // not happen is an upgrade INVENTING one, which would hand ownership of work to a worker that never
        // claimed it — so the assertion is on the count rather than on absence.
        assertThat(count(database, "execution_attempts where assignment_epoch is not null"
                        + " or assigned_worker_id is not null or lease_started_at is not null"
                        + " or lease_expires_at is not null or last_heartbeat_at is not null"
                        + " or fenced_at is not null"))
                .isEqualTo(2);
        assertThat(count(database, "execution_attempts where attempt_state <> 'WAITING_FOR_CLAIM'"))
                .isEqualTo(2);
        assertThat(count(database, "execution_attempts where attempt_state = 'CLAIMED'")).isZero();
        // And no pre-existing attempt acquires a HOLDER. acquired_at is new in V10, and materialising one would
        // assert that some worker had bound itself to work it never touched.
        assertThat(count(database, "execution_attempts where acquired_at is not null")).isZero();
        // The consumer inbox starts empty: it records decisions this deployment makes, and inventing one would
        // claim a message had been consumed that never arrived.
        assertThat(count(database, "dispatch_inbox")).isZero();
        assertThat(count(database, "pg_indexes where indexname = 'ix_execution_attempts_lease'")).isEqualTo(1);
        assertThat(count(database, "pg_indexes where indexname = 'ix_test_runs_stopping'")).isEqualTo(1);

        // No migration INVENTS authority. Granting permission to execute to an assignment that never asked for
        // it would point a live capability at a run whose worker is long gone.
        //
        // The counts are now exact rather than zero: the fixture seeds one authorization and two capabilities
        // so that V12's re-added type CHECK has rows to be validated against. Asserting the exact number is
        // what keeps the original claim intact — an upgrade that materialised one more would still fail here,
        // and so would one that silently dropped the fixture's own.
        assertThat(count(database, "execution_authorizations")).isEqualTo(1);
        assertThat(count(database, "execution_capabilities")).isEqualTo(2);
        assertThat(count(database, "execution_capabilities where capability_type = 'SOURCE'")).isEqualTo(1);
        assertThat(count(database, "execution_capabilities where capability_type = 'SECRET'")).isEqualTo(1);
        // And V12 invents no egress authority either. The type became legal; no row acquired it.
        assertThat(count(database, "execution_capabilities where capability_type = 'EGRESS'")).isZero();

        // Every project and every existing snapshot keeps the egress posture it already had. A migration that
        // defaulted these to anything but DENY_ALL would silently widen what work already in the system can
        // reach, which is the one direction an upgrade must never move on its own.
        assertThat(count(database, "projects where network_policy_revision_id"
                        + " <> '00000000-0000-4000-8000-00000000d001'")).isZero();
        assertThat(count(database, "run_snapshots where network_policy_revision_id"
                        + " <> '00000000-0000-4000-8000-00000000d001'")).isZero();
        // The seeded DENY_ALL stays platform-global. Giving it an owner would mean every project needed its
        // own copy, and the one project whose copy was missing would have no policy at all.
        assertThat(count(database, "network_policy_revisions where policy_type = 'DENY_ALL'"
                        + " and organization_id is null and project_id is null")).isEqualTo(1);
        // Policy versions are unique per owner now, not globally. The global form permitted exactly one
        // ALLOWLIST across the whole platform, so the second project to configure egress would have failed on
        // a unique violation naming a row belonging to a tenant it cannot see.
        assertThat(count(database, "pg_indexes where indexname = 'uq_network_policy_scope_type_version'"))
                .isEqualTo(1);
        assertThat(count(database, "pg_constraint where conname = 'uq_network_policy_type_version'")).isZero();
        assertThat(count(database, "execution_capability_secret_references")).isZero();
        assertThat(count(database, "execution_commands")).isZero();

        // Exactly one policy revision, seeded by the migration itself: DENY_ALL, version one, platform-authored,
        // and carrying the digest its own content implies. A digest that did not match would mean the seeded
        // row and the code that verifies it had drifted, and every authorization would refuse forever.
        //
        // The value is the v2 digest. V11 moved the canonical form to cover destinations — including a count of
        // zero — which changes every policy's digest, DENY_ALL included, and restates this row accordingly.
        assertThat(count(database, "network_policy_revisions")).isEqualTo(1);
        assertThat(count(database, "network_policy_revisions where policy_type = 'DENY_ALL'"
                        + " and policy_version = 1 and created_by = 'kaas.platform'"
                        + " and canonical_digest = "
                        + "'sha256:3944c369d57700eb13ce96b492fbac7ea9443a61faa8985a01e2394ab40e0de6'"))
                .isEqualTo(1);
        // The upgrade invents no destinations. ALLOWLIST is still unenforceable, and a seeded destination would
        // mean the platform had authorized egress nobody asked for.
        assertThat(count(database, "network_policy_destinations")).isZero();
        // And the immutability trigger V11 had to disable is enabled again. A migration that disabled a guard
        // and failed to restore it would leave every later write unguarded, silently.
        assertThat(count(database, "pg_trigger where tgname = 'network_policy_revisions_immutable'"
                        + " and tgrelid = 'network_policy_revisions'::regclass and tgenabled <> 'D'"))
                .isEqualTo(1);

        // V10 invents no execution either. An upgrade that materialised a phase deadline, a sandbox reference,
        // or a result would be claiming that work ran which never did — and for a terminal run it would be
        // claiming an outcome nobody produced.
        assertThat(count(database, "execution_results")).isZero();
        assertThat(count(database, "test_runs where phase_deadline_at is not null")).isZero();
        assertThat(count(database, "test_runs where execution_started_at is not null")).isZero();
        assertThat(count(database, "execution_attempts where provisioned_at is not null"
                        + " or execution_started_at is not null or execution_finished_at is not null"
                        + " or sandbox_reference is not null or infrastructure_disposition is not null"))
                .isZero();
        // No pre-existing run acquires an execution termination reason: those are reachable only by running.
        assertThat(count(database, "test_runs where termination_reason in"
                        + " ('EXECUTION_COMPLETED', 'EXECUTION_DEADLINE', 'PROVISIONING_DEADLINE',"
                        + " 'RESULT_DEADLINE', 'INFRASTRUCTURE_FAILURE')"))
                .isZero();
        assertThat(count(database, "pg_indexes where indexname = 'ix_test_runs_phase_deadline'")).isEqualTo(1);

        // The engine CHECK is the fourth arm V10 drops and re-adds, so it is revalidated against every row
        // already present. All ten fixture snapshots say KARATE — which is the point: the widening has to be
        // evaluated against rows carrying the OLD value, or it proves only that an empty table satisfies it.
        assertThat(count(database, "run_snapshots where engine = 'KARATE'")).isEqualTo(10);
        // And the upgrade rewrote none of them. Migrating existing snapshots to SYNTHETIC would be retroactively
        // changing what somebody's completed run claims to have executed.
        assertThat(count(database, "run_snapshots where engine <> 'KARATE'")).isZero();

        // The constraint was WIDENED, not removed. Asserted against its definition in the catalogue rather
        // than by offering it a bad value: snapshots are immutable, so an UPDATE is refused by the immutability
        // trigger long before the CHECK is consulted — a test written that way would pass while saying nothing
        // whatsoever about the CHECK. Both admitted values and the enumeration itself are asserted, so a
        // constraint that had quietly become `engine IS NOT NULL` would fail here.
        assertThat(count(database, "pg_constraint where conname = 'ck_run_snapshots_engine'"
                        + " and pg_get_constraintdef(oid) like '%SYNTHETIC%'"
                        + " and pg_get_constraintdef(oid) like '%KARATE%'"))
                .isEqualTo(1);
        assertThat(count(database, "pg_constraint where conname = 'ck_run_snapshots_engine'"
                        + " and pg_get_constraintdef(oid) like '%engine_version%'"))
                .isEqualTo(1);
    }

    /**
     * Seeds with {@code session_replication_role = replica}, which suspends triggers and foreign keys for the
     * fixture only. The migration under test then runs with every guard restored, which is the whole point: this
     * is what proves a migration can transform rows the previous version's triggers were protecting.
     */
    private void seedRepresentativeRows(String database) throws Exception {
        List<String> statements = new ArrayList<>();
        statements.add("""
            INSERT INTO projects (project_id, organization_id, name, created_by, created_at, updated_by, updated_at)
            VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000a0',
                    'Upgrade fixture', 'fixture', now(), 'fixture', now());

            INSERT INTO features (feature_id, organization_id, project_id, name, logical_path, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', 'Fixture feature', 'features/fixture.feature',
                    'fixture', now());
            INSERT INTO feature_revisions (revision_id, organization_id, project_id, feature_id, revision_number,
                    source, source_sha256, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', 1,
                    'Feature: fixture', repeat('a', 64), 'fixture', now());

            INSERT INTO environments (environment_id, organization_id, project_id, name, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', 'Fixture environment', 'fixture', now());
            INSERT INTO environment_revisions (revision_id, organization_id, project_id, environment_id,
                    revision_number, content_sha256, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000004', 1,
                    repeat('b', 64), 'fixture', now());

            INSERT INTO run_profiles (run_profile_id, organization_id, project_id, name, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', 'Fixture profile', 'fixture', now());
            INSERT INTO run_profile_revisions (revision_id, organization_id, project_id, run_profile_id,
                    revision_number, environment_id, environment_revision_id, parallelism, retry_max_attempts,
                    retry_delay_milliseconds, execution_timeout_seconds, max_artifact_bytes, max_total_bytes,
                    content_sha256, created_by, created_at)
            VALUES ('00000000-0000-4000-8000-000000000007', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000006', 1,
                    '00000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000005',
                    1, 1, 0, 60, 1000, 2000, repeat('c', 64), 'fixture', now());
            """);

        // One run still awaiting scheduling, plus four queued runs each carrying a complete bundle: attempt,
        // dispatch, lifecycle event, and exactly one outbox row. uq_outbox_dispatch is UNIQUE(dispatch_id), so
        // one outbox row per dispatch is the only shape production can hold.
        statements.add(runFixture("10", "CREATED", 1, "d", null));
        String[][] deliveries = {
            // suffix, published_at, publish_attempts, available_at, terminal, failure code
            {"21", "null", "0", "now()", "null", "null"},
            {"22", "now()", "1", "now()", "null", "null"},
            {"23", "null", "2", "now() + interval '1 hour'", "null", "'BROKER_UNAVAILABLE'"},
            {"24", "null", "5", "now()", "'RETRIES_EXHAUSTED'", "'BROKER_UNAVAILABLE'"}
        };
        for (String[] delivery : deliveries) {
            statements.add(runFixture(delivery[0], "QUEUED", 2, "e", delivery));
        }

        // Terminated runs, in all three shapes the previous version can actually produce.
        //
        // Without these the fixture is populated and still proves nothing about any constraint whose subject is a
        // terminated run: every termination CHECK short-circuits on `termination_reason IS NULL`, and only one of
        // the lifecycle-event branches is ever evaluated. A later migration could drop a disjunct — the kind of
        // slip a DROP-and-re-ADD rewrite invites — and ship green here while failing against the first real
        // database it met. These states became reachable one slice ago, so the next deployment holds them.
        statements.add(terminalRunFixture("31", "CREATED", 2, "USER_REQUESTED", "CANCELLATION", "CANCELLED"));
        statements.add(terminalRunFixture("32", "QUEUED", 3, "QUEUE_DEADLINE", "QUEUE", "TIMED_OUT"));
        statements.add(terminalRunFixture("33", "QUEUED", 3, "USER_REQUESTED", "CANCELLATION", "CANCELLED"));

        // A run stopping under a fenced assignment, which is the only shape that carries a stop reason.
        //
        // Added for V10, which drops and re-adds ck_test_runs_stop_reason. Without a row whose stop_reason is
        // non-null that rewrite is validated against nothing — every arm short-circuits on the NULL — so a
        // dropped value would ship green here and reject a real stopping run on the first deployment. This is
        // the same populated-but-unexercised gap the terminated runs above were added to close, one state later.
        statements.add(stoppingRunFixture("34"));
        statements.add(settledLeaseLostRunFixture("35"));

        // Capabilities that a deployment running the previous version would already hold.
        //
        // Added for V12, which DROPS and re-ADDS ck_execution_capabilities_type to admit a third value. A
        // re-added CHECK is validated against every row already in the table — and over an empty table it
        // validates nothing at all. This fixture previously seeded no capabilities, so V12 would have shipped
        // green here having never once been evaluated against a SOURCE or a SECRET row, which is exactly the
        // "a fresh-schema migration test is not a migration test" failure this class exists to catch, one
        // table deeper than it was looking.
        statements.add(executionAuthorityFixture("34"));

        // One RUN_STATE_CHANGED row as well, so the generalized schema is exercised beside the real type.
        statements.add("""
            INSERT INTO outbox_messages (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                    message_type, schema_version, aggregate_type, aggregate_id, payload, payload_sha256,
                    occurred_at, available_at, published_at, publish_attempts, last_attempt_at, last_failure_code,
                    terminal_disposition)
            VALUES ('00000000-0000-4000-8000-0000000000f5', null, gen_random_uuid(),
                    '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    '00000000-0000-4000-8000-000000000010', 'RUN_STATE_CHANGED', '1.0', 'TEST_RUN',
                    '00000000-0000-4000-8000-000000000010', '{"fixture":true}'::jsonb, repeat('9', 64),
                    now(), now(), null, 0, null, null, null);
            """);

        // One connection for the whole fixture: session_replication_role is a session setting, and opening a
        // fresh connection per statement would silently re-enable the guards midway through.
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (String sql : statements) {
                statement.execute(sql);
            }
            statement.execute("SET session_replication_role = origin");
        }
    }

    /**
     * A run that reached COMPLETED, with the lifecycle event that ended it.
     *
     * <p>Seeded through {@code session_replication_role = replica} like everything else here, so the shapes are
     * written directly rather than driven through the guards — but each one is a shape the previous version's
     * guards genuinely permit, which is the point: these are rows a real upgrade will meet.
     *
     * @param previousState the state the run was terminated from, which decides whether it has an attempt
     */
    private static String terminalRunFixture(
            String suffix,
            String previousState,
            int version,
            String terminationReason,
            String terminationPhase,
            String infrastructureOutcome) {
        String runId = "00000000-0000-4000-8000-0000000000" + suffix;
        String attemptId = "00000000-0000-4000-8000-0000000001" + suffix;
        boolean fromQueued = "QUEUED".equals(previousState);
        boolean cancelled = "USER_REQUESTED".equals(terminationReason);
        StringBuilder sql = new StringBuilder("""
            INSERT INTO test_runs (run_id, organization_id, project_id, run_version, lifecycle_state,
                    cancellation_status, test_outcome, infrastructure_outcome, quality_gate_status,
                    termination_reason, termination_phase, snapshot_sha256, queued_at, queue_deadline_at,
                    cancellation_requested_at, cancellation_acknowledged_at, completed_at, current_attempt_id,
                    created_by, created_at, updated_by, updated_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    %d, 'COMPLETED', '%s', 'NOT_AVAILABLE', '%s', 'NOT_EVALUATED', '%s', '%s',
                    repeat('7', 64), %s, %s, %s, %s, now(), %s,
                    'fixture', now(), '%s', now());
            INSERT INTO run_snapshots (run_id, organization_id, project_id, snapshot_version, run_profile_id,
                    run_profile_revision_id, run_profile_revision_number, run_profile_sha256, environment_id,
                    environment_revision_id, environment_revision_number, environment_sha256, parallelism,
                    retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                    max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256, sealed)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001', 1,
                    '00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000007', 1,
                    repeat('c', 64), '00000000-0000-4000-8000-000000000004',
                    '00000000-0000-4000-8000-000000000005', 1, repeat('b', 64), 1, 1, 0, 60, 1000, 2000,
                    'KARATE', '2.0.0', repeat('7', 64), true);
            """
                .formatted(
                        runId, version,
                        cancelled ? "ACKNOWLEDGED" : "NOT_REQUESTED",
                        infrastructureOutcome, terminationReason, terminationPhase,
                        fromQueued ? "now()" : "null",
                        fromQueued ? "now() + interval '5 min'" : "null",
                        cancelled ? "now()" : "null",
                        cancelled ? "now()" : "null",
                        fromQueued ? "'" + attemptId + "'" : "null",
                        cancelled ? "fixture-principal" : "kaas.queue-reaper",
                        runId));
        if (fromQueued) {
            // A run terminated from QUEUED keeps the scheduling bundle it earned, so the fixture must too — and
            // its dispatch was withdrawn rather than delivered, which is the outbox shape V7 introduced.
            sql.append("""
                INSERT INTO execution_attempts (attempt_id, organization_id, project_id, run_id, attempt_number,
                        attempt_state, created_by, created_at)
                VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                        '%s', 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now());
                INSERT INTO run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                        sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                VALUES ('00000000-0000-4000-8000-0000000006%s', '00000000-0000-4000-8000-0000000000a0',
                        '00000000-0000-4000-8000-000000000001', '%s', 2, 1, 'RUN_STATE_CHANGED', 'CREATED',
                        'QUEUED', '%s', 'kaas.scheduler', now());
                """
                    .formatted(attemptId, runId, suffix, runId, attemptId));
        }
        // The terminal event itself: sequence = run_version - 1, which is the branch the new CHECK adds.
        sql.append("""
            INSERT INTO run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                    sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
            VALUES ('00000000-0000-4000-8000-0000000007%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', %d, %d, 'RUN_STATE_CHANGED', '%s',
                    'COMPLETED', %s, '%s', now());
            """
                .formatted(
                        suffix, runId, version, version - 1, previousState,
                        fromQueued ? "'" + attemptId + "'" : "null",
                        cancelled ? "fixture-principal" : "kaas.queue-reaper"));
        return sql.toString();
    }

    /**
     * One run and, when it is queued, the complete bundle production would hold for it: snapshot, attempt,
     * dispatch, lifecycle event, and exactly one dispatch-backed outbox row in the given delivery state.
     *
     * <p>The outbox row being dispatch-backed is the point. A fixture of rows with a null {@code dispatch_id}
     * would be skipped entirely by any backfill that joins or filters on the dispatch, which is precisely the
     * shape of the migration defect this gate exists to catch — it would pass green and prove nothing.
     */
    /**
     * A run in STOPPING under a fenced assignment: the one state that carries a stop reason.
     *
     * <p>Seeded at the previous version's shape, so the upgrade under test is what has to accept it.
     */
    /**
     * A run that was owned, lost its lease, and settled.
     *
     * <p>Present because V10 revalidates the termination vocabulary and the lifecycle-event transition CHECK
     * against every existing row, and without this the {@code LEASE_LOST/CLAIM/FAILED} arm and the
     * {@code STOPPING -> COMPLETED} edge were validated against nothing. A rewrite that dropped either would
     * have shipped green and then refused real production rows on first deployment — which is the exact failure
     * this class exists to prevent, one level deeper than it was looking.
     *
     * <p>It also carries the only row whose {@code termination_reason} equals its {@code stop_reason}, which is
     * what {@code ck_test_runs_stop_reason_agrees} actually constrains; every other fixture leaves that
     * disjunct short-circuited and the constraint unexercised.
     */
    private static String settledLeaseLostRunFixture(String suffix) {
        String runId = "00000000-0000-4000-8000-0000000000" + suffix;
        String attemptId = "00000000-0000-4000-8000-0000000001" + suffix;
        return """
            INSERT INTO test_runs (run_id, organization_id, project_id, run_version, lifecycle_state,
                    cancellation_status, test_outcome, infrastructure_outcome, quality_gate_status,
                    termination_reason, termination_phase, stop_reason, snapshot_sha256, queued_at,
                    queue_deadline_at, completed_at, current_attempt_id, created_by, created_at, updated_by,
                    updated_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    5, 'COMPLETED', 'NOT_REQUESTED', 'NOT_AVAILABLE', 'FAILED', 'NOT_EVALUATED',
                    'LEASE_LOST', 'CLAIM', 'LEASE_LOST', repeat('9', 64), now(),
                    now() + interval '5 min', now(), '%s', 'fixture', now(), 'kaas.lease-reconciler', now());
            INSERT INTO run_snapshots (run_id, organization_id, project_id, snapshot_version, run_profile_id,
                    run_profile_revision_id, run_profile_revision_number, run_profile_sha256, environment_id,
                    environment_revision_id, environment_revision_number, environment_sha256, parallelism,
                    retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                    max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256, sealed)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001', 1,
                    '00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000007', 1,
                    repeat('c', 64), '00000000-0000-4000-8000-000000000004',
                    '00000000-0000-4000-8000-000000000005', 1, repeat('b', 64), 1, 1, 0, 60, 1000, 2000,
                    'KARATE', '2.0.0', repeat('9', 64), true);
            INSERT INTO execution_attempts (attempt_id, organization_id, project_id, run_id, attempt_number,
                    attempt_state, assignment_epoch, assigned_worker_id, lease_started_at, lease_expires_at,
                    last_heartbeat_at, fenced_at, created_by, created_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    '%s', 1, 'FENCED', 1, 'kaas.worker.fixture', now() - interval '2 min',
                    now() - interval '1 min', now() - interval '2 min', now(), 'kaas.scheduler', now());
            INSERT INTO run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                    sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
            VALUES ('00000000-0000-4000-8000-0000000005%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 2, 1, 'RUN_STATE_CHANGED', 'CREATED',
                    'QUEUED', '%s', 'kaas.scheduler', now()),
                   ('00000000-0000-4000-8000-0000000006%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 3, 2, 'RUN_STATE_CHANGED', 'QUEUED',
                    'CLAIMED', '%s', 'kaas.dispatch-consumer', now()),
                   ('00000000-0000-4000-8000-0000000007%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 4, 3, 'RUN_STATE_CHANGED', 'CLAIMED',
                    'STOPPING', '%s', 'kaas.lease-reconciler', now()),
                   ('00000000-0000-4000-8000-0000000008%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 5, 4, 'RUN_STATE_CHANGED', 'STOPPING',
                    'COMPLETED', '%s', 'kaas.lease-reconciler', now());
            """
                .formatted(
                        runId, attemptId, runId, attemptId, runId,
                        suffix, runId, attemptId,
                        suffix, runId, attemptId,
                        suffix, runId, attemptId,
                        suffix, runId, attemptId);
    }

    private static String stoppingRunFixture(String suffix) {
        String runId = "00000000-0000-4000-8000-0000000000" + suffix;
        String attemptId = "00000000-0000-4000-8000-0000000001" + suffix;
        return """
            INSERT INTO test_runs (run_id, organization_id, project_id, run_version, lifecycle_state,
                    cancellation_status, quality_gate_status, stop_reason, snapshot_sha256, queued_at,
                    queue_deadline_at, current_attempt_id, created_by, created_at, updated_by, updated_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    4, 'STOPPING', 'NOT_REQUESTED', 'NOT_EVALUATED', 'LEASE_LOST', repeat('8', 64), now(),
                    now() + interval '5 min', '%s', 'fixture', now(), 'kaas.lease-reconciler', now());
            INSERT INTO run_snapshots (run_id, organization_id, project_id, snapshot_version, run_profile_id,
                    run_profile_revision_id, run_profile_revision_number, run_profile_sha256, environment_id,
                    environment_revision_id, environment_revision_number, environment_sha256, parallelism,
                    retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                    max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256, sealed)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001', 1,
                    '00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000007', 1,
                    repeat('c', 64), '00000000-0000-4000-8000-000000000004',
                    '00000000-0000-4000-8000-000000000005', 1, repeat('b', 64), 1, 1, 0, 60, 1000, 2000,
                    'KARATE', '2.0.0', repeat('8', 64), true);
            INSERT INTO execution_attempts (attempt_id, organization_id, project_id, run_id, attempt_number,
                    attempt_state, assignment_epoch, assigned_worker_id, lease_started_at, lease_expires_at,
                    last_heartbeat_at, fenced_at, created_by, created_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    '%s', 1, 'FENCED', 1, 'kaas.worker.fixture', now() - interval '1 min',
                    now() + interval '1 min', now() - interval '1 min', now(), 'kaas.scheduler', now());
            INSERT INTO run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                    sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
            VALUES ('00000000-0000-4000-8000-0000000002%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 2, 1, 'RUN_STATE_CHANGED', 'CREATED',
                    'QUEUED', '%s', 'kaas.scheduler', now()),
                   ('00000000-0000-4000-8000-0000000003%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 3, 2, 'RUN_STATE_CHANGED', 'QUEUED',
                    'CLAIMED', '%s', 'kaas.dispatch-consumer', now()),
                   ('00000000-0000-4000-8000-0000000004%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 4, 3, 'RUN_STATE_CHANGED', 'CLAIMED',
                    'STOPPING', '%s', 'kaas.lease-reconciler', now());
            """
                .formatted(
                        runId, attemptId, runId, attemptId, runId,
                        suffix, runId, attemptId,
                        suffix, runId, attemptId,
                        suffix, runId, attemptId);
    }

    private static String runFixture(String suffix, String lifecycle, int version, String digest, String[] delivery) {
        String runId = "00000000-0000-4000-8000-0000000000" + suffix;
        String attemptId = "00000000-0000-4000-8000-0000000001" + suffix;
        String dispatchId = "00000000-0000-4000-8000-0000000002" + suffix;
        boolean queued = "QUEUED".equals(lifecycle);
        StringBuilder sql = new StringBuilder("""
            INSERT INTO test_runs (run_id, organization_id, project_id, run_version, lifecycle_state,
                    cancellation_status, quality_gate_status, snapshot_sha256, queued_at, queue_deadline_at,
                    current_attempt_id, created_by, created_at, updated_by, updated_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    %d, '%s', 'NOT_REQUESTED', 'NOT_EVALUATED', repeat('%s', 64), %s, %s, %s,
                    'fixture', now(), 'fixture', now());
            INSERT INTO run_snapshots (run_id, organization_id, project_id, snapshot_version, run_profile_id,
                    run_profile_revision_id, run_profile_revision_number, run_profile_sha256, environment_id,
                    environment_revision_id, environment_revision_number, environment_sha256, parallelism,
                    retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                    max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256, sealed)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001', 1,
                    '00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000007', 1,
                    repeat('c', 64), '00000000-0000-4000-8000-000000000004',
                    '00000000-0000-4000-8000-000000000005', 1, repeat('b', 64), 1, 1, 0, 60, 1000, 2000,
                    'KARATE', '2.0.0', repeat('%s', 64), true);
            """
                .formatted(
                        runId, version, lifecycle, digest,
                        queued ? "now()" : "null",
                        queued ? "now() + interval '5 min'" : "null",
                        queued ? "'" + attemptId + "'" : "null",
                        runId, digest));
        if (!queued) {
            return sql.toString();
        }
        sql.append("""
            INSERT INTO execution_attempts (attempt_id, organization_id, project_id, run_id, attempt_number,
                    attempt_state, created_by, created_at)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    '%s', 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now());
            INSERT INTO execution_dispatches (dispatch_id, message_id, organization_id, project_id, run_id,
                    run_version, attempt_id, attempt_number, run_snapshot_id, run_snapshot_sha256, schema_version,
                    message_type, producer, occurred_at, queue_deadline_at, payload, payload_sha256)
            VALUES ('%s', '00000000-0000-4000-8000-0000000003%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 2, '%s', 1, '%s', repeat('%s', 64), '1.0',
                    'EXECUTION_DISPATCH', 'kaas.scheduler', now(), now() + interval '5 min',
                    '{"runId":"%s"}'::jsonb, repeat('f', 64));
            INSERT INTO run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version, sequence,
                    event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
            VALUES ('00000000-0000-4000-8000-0000000004%s', '00000000-0000-4000-8000-0000000000a0',
                    '00000000-0000-4000-8000-000000000001', '%s', 2, 1, 'RUN_STATE_CHANGED', 'CREATED', 'QUEUED',
                    '%s', 'kaas.scheduler', now());
            INSERT INTO outbox_messages (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                    message_type, schema_version, aggregate_type, aggregate_id, payload, payload_sha256,
                    occurred_at, available_at, published_at, publish_attempts, last_attempt_at, last_failure_code,
                    terminal_disposition)
            SELECT '00000000-0000-4000-8000-0000000005%s', d.dispatch_id, d.message_id, d.organization_id,
                   d.project_id, d.run_id, 'EXECUTION_DISPATCH', d.schema_version, 'TEST_RUN', d.run_id,
                   d.payload, d.payload_sha256, d.occurred_at, %s, %s, %s, %s, %s, %s
              FROM execution_dispatches d WHERE d.dispatch_id = '%s';
            """
                .formatted(
                        attemptId, runId,
                        dispatchId, suffix, runId, attemptId, runId, digest, runId,
                        suffix, runId, attemptId,
                        suffix, delivery[3], delivery[1], delivery[2],
                        "0".equals(delivery[2]) ? "null" : "now()", delivery[5], delivery[4],
                        dispatchId));
        return sql.toString();
    }

    /**
     * An authorization and its capabilities, at the shape the previous version produces.
     *
     * <p>Hung off the fenced attempt of the stopping run rather than off a fresh claimed one, for two reasons.
     * It is the realistic shape — an authorization outlives the assignment it was issued for and is revoked
     * rather than deleted, because it is audit evidence. And materialising a CLAIMED attempt here would
     * contradict the assertion elsewhere in this class that no pre-existing attempt is claimed, which exists
     * because a claimed attempt implies a live worker that this database has never met.
     *
     * <p>One capability is redeemed and one is not, so that both sides of the redemption accounting are
     * present for any constraint that reasons about it.
     */
    private static String executionAuthorityFixture(String suffix) {
        String runId = "00000000-0000-4000-8000-0000000000" + suffix;
        String attemptId = "00000000-0000-4000-8000-0000000001" + suffix;
        String authorizationId = "00000000-0000-4000-8000-0000000006" + suffix;
        return """
            INSERT INTO execution_authorizations (authorization_id, organization_id, project_id, run_id,
                    run_version, attempt_id, attempt_number, assignment_epoch, worker_id, run_snapshot_sha256,
                    security_profile_version, security_assessment_digest, probe_image_digest,
                    network_policy_revision_id, issued_at, expires_at, revoked_at, revoked_reason)
            VALUES ('%s', '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    '%s', 4, '%s', 1, 1, 'kaas.worker.fixture', repeat('8', 64), 'kaas.sandbox.v1',
                    'sha256:' || repeat('a', 64), 'sha256:' || repeat('b', 64),
                    '00000000-0000-4000-8000-00000000d001', now() - interval '2 min',
                    now() + interval '3 min', now() - interval '1 min', 'ASSIGNMENT_FENCED');
            INSERT INTO execution_capabilities (capability_id, authorization_id, organization_id, project_id,
                    capability_type, token_sha256, issued_at, expires_at, redemption_count, last_redeemed_at,
                    revoked_at)
            VALUES ('00000000-0000-4000-8000-0000000007%s', '%s',
                    '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    'SOURCE', repeat('c', 64), now() - interval '2 min', now() + interval '3 min',
                    3, now() - interval '90 seconds', now() - interval '1 min'),
                   ('00000000-0000-4000-8000-0000000008%s', '%s',
                    '00000000-0000-4000-8000-0000000000a0', '00000000-0000-4000-8000-000000000001',
                    'SECRET', repeat('d', 64), now() - interval '2 min', now() + interval '3 min',
                    0, null, now() - interval '1 min');
            """
                .formatted(authorizationId, runId, attemptId, suffix, authorizationId, suffix, authorizationId);
    }

    /** Migration versions as they exist on disk, in applied order. The source of truth is the files themselves. */
    private static List<String> versionsOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .sorted(Comparator.comparingInt(Integer::parseInt))
                    .toList();
        }
    }

    private static List<String> appliedVersions(String database) throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select version from flyway_schema_history where success and version is not null"
                                + " order by installed_rank")) {
            while (resultSet.next()) {
                versions.add(resultSet.getString(1));
            }
        }
        return versions;
    }

    private static int count(String database, String fromClause) throws Exception {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select count(*) from " + fromClause)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void execute(String database, String sql) throws Exception {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Each test gets its own database inside the shared container, so the two directions cannot interfere. */
    private static String freshDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + name);
            statement.execute("create database " + name);
        }
        return name;
    }

    private static Connection connect(String database) throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        return url.substring(0, url.lastIndexOf('/') + 1) + database + urlParameters(url);
    }

    private static String urlParameters(String url) {
        int query = url.indexOf('?');
        return query < 0 ? "" : url.substring(query);
    }
}
