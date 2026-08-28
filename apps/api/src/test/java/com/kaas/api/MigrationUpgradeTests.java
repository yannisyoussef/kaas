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
 *   <li>every migration applies to a database already carrying representative rows from the previous version.
 * </ul>
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

        // The remaining migrations now run against real rows, with every runtime trigger of the previous version
        // still installed. A migration that transforms data must cope with the guards that were protecting it.
        Flyway.configure().dataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();

        assertThat(appliedVersions(database)).isEqualTo(versions);
        assertRepresentativeRowsSurvived(database);
    }

    /**
     * Proves, before the upgrade runs, that the fixture actually holds rows the pending migrations will act on.
     *
     * <p>This is the same rule as for a backfill, applied to the other way a migration touches existing data. A
     * migration that adds a validating CHECK, drops a NOT NULL, or replaces a guard is checked against every row
     * already in the table — so over an empty table it proves exactly nothing, and would pass while shipping a
     * constraint production data violates. Asserting emptiness here is the point: if a future fixture change
     * empties one of these, this fails loudly instead of going quietly green.
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
    }

    /** Everything the upgrade must not lose, damage, or silently rewrite. */
    private void assertRepresentativeRowsSurvived(String database) throws Exception {
        assertThat(count(database, "projects")).isEqualTo(1);
        assertThat(count(database, "feature_revisions")).isEqualTo(1);
        assertThat(count(database, "environment_revisions")).isEqualTo(1);
        assertThat(count(database, "run_profile_revisions")).isEqualTo(1);
        assertThat(count(database, "run_snapshots")).isEqualTo(5);
        // One CREATED run and one QUEUED run, with the QUEUED run's full scheduling bundle intact.
        assertThat(count(database, "test_runs where lifecycle_state = 'CREATED'")).isEqualTo(1);
        assertThat(count(database, "test_runs where lifecycle_state = 'QUEUED'")).isEqualTo(4);
        assertThat(count(database, "execution_attempts")).isEqualTo(4);
        assertThat(count(database, "execution_dispatches")).isEqualTo(4);
        assertThat(count(database, "run_lifecycle_events")).isEqualTo(4);

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
        assertThat(count(database, "test_runs where completed_at is not null"
                        + " or termination_reason is not null or termination_phase is not null"
                        + " or cancellation_requested_at is not null"
                        + " or cancellation_acknowledged_at is not null"))
                .isZero();
        assertThat(count(database, "test_runs where lifecycle_state <> 'COMPLETED'")).isEqualTo(5);
        // The lifecycle events kept their attempts; relaxing the column did not blank anything.
        assertThat(count(database, "run_lifecycle_events where attempt_id is null")).isZero();
        // And no delivery state was reinterpreted as a suppression.
        assertThat(count(database, "outbox_messages where terminal_disposition like 'SUPPRESSED%'")).isZero();
        assertThat(count(database, "outbox_messages where terminal_disposition = 'RETRIES_EXHAUSTED'"))
                .isEqualTo(1);
        // The reaper's index exists, because a queue deadline nothing can select efficiently is a promise the
        // platform cannot keep at scale.
        assertThat(count(database, "pg_indexes where indexname = 'ix_test_runs_queue_deadline'")).isEqualTo(1);
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
     * One run and, when it is queued, the complete bundle production would hold for it: snapshot, attempt,
     * dispatch, lifecycle event, and exactly one dispatch-backed outbox row in the given delivery state.
     *
     * <p>The outbox row being dispatch-backed is the point. A fixture of rows with a null {@code dispatch_id}
     * would be skipped entirely by any backfill that joins or filters on the dispatch, which is precisely the
     * shape of the migration defect this gate exists to catch — it would pass green and prove nothing.
     */
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
