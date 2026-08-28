package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.controlplane.application.RunSchedulingService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.ExecutionDispatchPolicy;
import com.kaas.api.controlplane.domain.ScheduleDisposition;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.shared.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the single implemented runtime lifecycle transition, CREATED to QUEUED. Scheduling is an internal
 * application use case: it is reached through {@link RunSchedulingService} rather than an HTTP endpoint, because the
 * public API deliberately exposes no scheduling operation. Nothing here publishes to a broker, claims an attempt, or
 * produces an ExecutionCommand.
 */
@Testcontainers
@Import(SchedulingHttpIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.queue-timeout=PT7M",
            // The concurrency tests park one connection per blocked scheduler; leave headroom over SCHEDULERS.
            "spring.datasource.hikari.maximum-pool-size=16",
            // This suite drives scheduling explicitly and asserts exact state, so the timers must stay out of it.
            "kaas.scheduling.auto.enabled=false",
            "kaas.outbox.relay.enabled=false"
        })
class SchedulingHttpIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    /** Every field the queue-time DispatchIntent must never carry, because it is claim-time execution authority. */
    private static final List<String> CLAIM_TIME_AUTHORITY = List.of(
            "assignmentEpoch", "workerId", "worker", "lease", "leaseId", "leaseExpiresAt", "capability",
            "secretCapability", "sourceCapability", "secretValue", "presigned", "objectStoreUrl", "routingKey",
            "exchange", "docker", "image", "hostPath", "credential", "token", "source", "feature", "script");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-scheduling");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${kaas.scheduling.queue-timeout}")
    private Duration queueTimeout;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RunSchedulingService scheduler;

    @Test
    void schedulingAtomicallyQueuesTheRunWithExactlyOneUnpublishedDispatchIntent() throws Exception {
        CreatedRun created = createRun();

        assertThat(created.run().get("runVersion").asInt()).isEqualTo(1);
        assertThat(created.run().get("lifecycleState").stringValue()).isEqualTo("CREATED");
        assertThat(created.run().get("queueStartedAt").isNull()).isTrue();
        assertThat(created.run().get("queueDeadlineAt").isNull()).isTrue();
        assertThat(count("execution_attempts", created.runId())).isZero();
        assertThat(count("execution_dispatches", created.runId())).isZero();
        assertThat(count("outbox_messages", created.runId())).isZero();
        assertThat(count("run_lifecycle_events", created.runId())).isZero();

        Instant beforeSchedule = databaseNow();
        var result = scheduler.schedule(created.organizationId(), created.runId(), 1);
        Instant afterSchedule = databaseNow();

        assertThat(result.disposition()).isEqualTo(ScheduleDisposition.SCHEDULED);
        TestRun queued = result.run();
        assertThat(queued.lifecycleState().name()).isEqualTo("QUEUED");
        assertThat(queued.runVersion()).isEqualTo(2);
        assertThat(queued.queueStartedAt()).isBetween(beforeSchedule, afterSchedule);
        assertThat(queued.queueDeadlineAt()).isEqualTo(queued.queueStartedAt().plus(queueTimeout));

        // The public read model reflects the transition, including the semantic ETag.
        var fetched = get("/api/v1/runs/" + created.runId(), created.token());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("ETag")).contains("\"run-2\"");
        JsonNode run = json(fetched);
        assertThat(run.get("lifecycleState").stringValue()).isEqualTo("QUEUED");
        assertThat(run.get("runVersion").asInt()).isEqualTo(2);
        assertThat(run.get("queueStartedAt").isNull()).isFalse();
        assertThat(run.get("queueDeadlineAt").isNull()).isFalse();
        assertThat(Instant.parse(run.get("queueDeadlineAt").stringValue()))
                .isEqualTo(Instant.parse(run.get("queueStartedAt").stringValue()).plus(queueTimeout));
        // The attempt is infrastructure history and stays out of the public representation.
        assertThat(run.has("currentAttempt")).isFalse();
        assertThat(run.has("currentAttemptId")).isFalse();
        assertThat(run.has("attemptId")).isFalse();

        // Exactly one attempt, awaiting a claim that no implemented code can perform.
        Map<String, Object> attempt = jdbc.queryForMap(
                "select * from execution_attempts where run_id = ?", created.runId());
        assertThat(count("execution_attempts", created.runId())).isEqualTo(1);
        assertThat(attempt.get("attempt_number")).isEqualTo(1);
        assertThat(attempt.get("attempt_state")).isEqualTo("WAITING_FOR_CLAIM");
        assertThat(attempt.get("organization_id")).isEqualTo(created.organizationId());
        assertThat(jdbc.queryForObject(
                        "select current_attempt_id from test_runs where run_id = ?", UUID.class, created.runId()))
                .isEqualTo(attempt.get("attempt_id"));
        // No assignment exists yet: the schema itself offers nowhere to record one.
        assertThat(columnsOf("execution_attempts"))
                .containsExactlyInAnyOrder(
                        "attempt_id", "organization_id", "project_id", "run_id", "attempt_number",
                        "attempt_state", "created_by", "created_at");

        // Exactly one dispatch, bound to the sealed snapshot.
        Map<String, Object> dispatchRow = jdbc.queryForMap(
                "select * from execution_dispatches where run_id = ?", created.runId());
        assertThat(count("execution_dispatches", created.runId())).isEqualTo(1);
        assertThat(dispatchRow.get("attempt_id")).isEqualTo(attempt.get("attempt_id"));
        assertThat(dispatchRow.get("run_version")).isEqualTo(2L);
        assertThat(dispatchRow.get("run_snapshot_id")).isEqualTo(created.runId());
        assertThat("sha256:" + dispatchRow.get("run_snapshot_sha256"))
                .isEqualTo(created.run().get("snapshotDigest").stringValue());
        assertThat(dispatchRow.get("producer")).isEqualTo("kaas.scheduler");
        assertThat(dispatchRow.get("message_type")).isEqualTo("EXECUTION_DISPATCH");

        // Exactly one outbox record, durable and unpublished. Nothing publishes it.
        Map<String, Object> outbox = jdbc.queryForMap(
                "select * from outbox_messages where run_id = ?", created.runId());
        assertThat(count("outbox_messages", created.runId())).isEqualTo(1);
        assertThat(outbox.get("published_at")).isNull();
        assertThat(outbox.get("publish_attempts")).isEqualTo(0);
        assertThat(outbox.get("last_failure_code")).isNull();
        assertThat(outbox.get("message_id")).isEqualTo(dispatchRow.get("message_id"));
        assertThat(outbox.get("payload_sha256")).isEqualTo(dispatchRow.get("payload_sha256"));
        assertThat(outbox.get("aggregate_id")).isEqualTo(created.runId());

        assertDispatchPayloadIsCanonicalAndCarriesNoExecutionAuthority(created.runId());

        // Repeat scheduling is a no-op: same durable work, same queue deadline, same version.
        var repeat = scheduler.schedule(created.organizationId(), created.runId(), 1);
        assertThat(repeat.disposition()).isEqualTo(ScheduleDisposition.ALREADY_SCHEDULED);
        var repeatAtCurrentVersion = scheduler.schedule(created.organizationId(), created.runId(), 2);
        assertThat(repeatAtCurrentVersion.disposition()).isEqualTo(ScheduleDisposition.ALREADY_SCHEDULED);
        assertThat(count("execution_attempts", created.runId())).isEqualTo(1);
        assertThat(count("execution_dispatches", created.runId())).isEqualTo(1);
        assertThat(count("outbox_messages", created.runId())).isEqualTo(1);
        assertThat(count("run_lifecycle_events", created.runId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("select * from test_runs where run_id = ?", created.runId()))
                .containsEntry("run_version", 2L)
                .containsEntry("queue_deadline_at", dispatchRow.get("queue_deadline_at"));
        assertThat(get("/api/v1/runs/" + created.runId(), created.token()).headers().firstValue("ETag"))
                .contains("\"run-2\"");
    }

    @Test
    @Timeout(120)
    void tenConcurrentSchedulersProduceExactlyOneSemanticWinner() throws Exception {
        CreatedRun created = createRun();
        int schedulers = 10;
        var barrier = new CyclicBarrier(schedulers);

        try (var pool = Executors.newFixedThreadPool(schedulers)) {
            List<Attempt> attempts = pool
                    .invokeAll(IntStream.range(0, schedulers)
                            .<java.util.concurrent.Callable<Attempt>>mapToObj(index -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                long startedAt = System.nanoTime();
                                var disposition =
                                        scheduler.schedule(created.organizationId(), created.runId(), 1)
                                                .disposition();
                                return new Attempt(disposition, startedAt, System.nanoTime());
                            })
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get(60, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent scheduling failed", exception);
                        }
                    })
                    .toList();

            List<ScheduleDisposition> dispositions = attempts.stream().map(Attempt::disposition).toList();
            assertThat(dispositions).filteredOn(ScheduleDisposition.SCHEDULED::equals).hasSize(1);
            assertThat(dispositions).filteredOn(ScheduleDisposition.ALREADY_SCHEDULED::equals).hasSize(schedulers - 1);
            // A serialized implementation would produce the same disposition counts, so assert the calls genuinely
            // overlapped in time and the race was real.
            assertThat(overlapping(attempts))
                    .as("at least two scheduling calls must actually run concurrently")
                    .isTrue();
        }

        // One transition, one attempt, one dispatch, one outbox message. No duplicate execution intent.
        assertThat(jdbc.queryForObject(
                        "select run_version from test_runs where run_id = ?", Long.class, created.runId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, created.runId()))
                .isEqualTo("QUEUED");
        assertThat(count("execution_attempts", created.runId())).isEqualTo(1);
        assertThat(count("execution_dispatches", created.runId())).isEqualTo(1);
        assertThat(count("outbox_messages", created.runId())).isEqualTo(1);
        assertThat(count("run_lifecycle_events", created.runId())).isEqualTo(1);
    }

    private record Attempt(ScheduleDisposition disposition, long startedAt, long finishedAt) {}

    private static boolean overlapping(List<Attempt> attempts) {
        for (int left = 0; left < attempts.size(); left++) {
            for (int right = left + 1; right < attempts.size(); right++) {
                Attempt a = attempts.get(left);
                Attempt b = attempts.get(right);
                if (a.startedAt() < b.finishedAt() && b.startedAt() < a.finishedAt()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @Timeout(120)
    void schedulingDistinctRunsConcurrentlyKeepsEachBundleIndependent() throws Exception {
        // Every scheduling invariant is per-run. If any guard queried across runs instead of scoping to its own,
        // concurrent scheduling of unrelated runs would deadlock or reject a legitimate bundle.
        int runs = 6;
        List<CreatedRun> created = IntStream.range(0, runs)
                .mapToObj(index -> {
                    try {
                        return createRun();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        var barrier = new CyclicBarrier(runs);

        try (var pool = Executors.newFixedThreadPool(runs)) {
            List<ScheduleDisposition> dispositions = pool
                    .invokeAll(created.stream()
                            .<java.util.concurrent.Callable<ScheduleDisposition>>map(run -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                return scheduler.schedule(run.organizationId(), run.runId(), 1).disposition();
                            })
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get(60, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent scheduling of distinct runs failed", exception);
                        }
                    })
                    .toList();
            assertThat(dispositions).containsOnly(ScheduleDisposition.SCHEDULED);
        }

        for (CreatedRun run : created) {
            assertThat(jdbc.queryForObject(
                            "select lifecycle_state from test_runs where run_id = ?", String.class, run.runId()))
                    .isEqualTo("QUEUED");
            assertThat(count("execution_attempts", run.runId())).isEqualTo(1);
            assertThat(count("execution_dispatches", run.runId())).isEqualTo(1);
            assertThat(count("outbox_messages", run.runId())).isEqualTo(1);
        }
    }

    @Test
    void aFailureAfterTheSchedulingWritesLeavesTheRunCreatedWithNoDurableExecutionIntent() throws Exception {
        CreatedRun created = createRun();
        var transactions = new TransactionTemplate(transactionManager);

        // The scheduler joins the caller's transaction, so failing anywhere before commit must undo every write:
        // the lifecycle transition, the attempt, the dispatch, the lifecycle event, and the outbox record.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    var result = scheduler.schedule(created.organizationId(), created.runId(), 1);
                    assertThat(result.disposition()).isEqualTo(ScheduleDisposition.SCHEDULED);
                    throw new IllegalStateException("scheduling failed after the durable writes");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduling failed after the durable writes");

        Map<String, Object> run = jdbc.queryForMap("select * from test_runs where run_id = ?", created.runId());
        assertThat(run.get("lifecycle_state")).isEqualTo("CREATED");
        assertThat(run.get("run_version")).isEqualTo(1L);
        assertThat(run.get("queued_at")).isNull();
        assertThat(run.get("queue_deadline_at")).isNull();
        assertThat(run.get("current_attempt_id")).isNull();
        assertThat(count("execution_attempts", created.runId())).isZero();
        assertThat(count("execution_dispatches", created.runId())).isZero();
        assertThat(count("outbox_messages", created.runId())).isZero();
        assertThat(count("run_lifecycle_events", created.runId())).isZero();

        // The run is still schedulable afterwards, at its original expected version.
        assertThat(scheduler.schedule(created.organizationId(), created.runId(), 1).disposition())
                .isEqualTo(ScheduleDisposition.SCHEDULED);
        assertThat(count("execution_attempts", created.runId())).isEqualTo(1);
    }

    @Test
    void staleAndForeignSchedulingIsRejectedWithoutCreatingExecutionIntent() throws Exception {
        CreatedRun created = createRun();

        // A stale expected version never transitions the run.
        var stale = scheduler.schedule(created.organizationId(), created.runId(), 99);
        assertThat(stale.disposition()).isEqualTo(ScheduleDisposition.STALE_VERSION);
        assertThat(stale.run().lifecycleState().name()).isEqualTo("CREATED");

        // A foreign organization cannot schedule, and the run's existence stays concealed.
        UUID foreignOrganization = UUID.randomUUID();
        assertThatThrownBy(() -> scheduler.schedule(foreignOrganization, created.runId(), 1))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).status().value()).isEqualTo(404));

        // An unknown run is equally concealed.
        assertThatThrownBy(() -> scheduler.schedule(created.organizationId(), UUID.randomUUID(), 1))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, created.runId()))
                .isEqualTo("CREATED");
        assertThat(count("execution_attempts", created.runId())).isZero();
        assertThat(count("execution_dispatches", created.runId())).isZero();
        assertThat(count("outbox_messages", created.runId())).isZero();

        // Composite ownership rejects a cross-tenant attempt forged directly against the database.
        scheduler.schedule(created.organizationId(), created.runId(), 1);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into execution_attempts
                            (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                             created_by, created_at)
                        values (?, ?, ?, ?, 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now())
                        """,
                        UUID.randomUUID(), foreignOrganization, created.projectId(), created.runId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsEveryMutationExceptTheImplementedScheduleTransition() throws Exception {
        CreatedRun created = createRun();
        UUID runId = created.runId();
        String scheduleGuard = "only scheduling and early terminal transitions are supported";

        // A CREATED run cannot be pushed straight into a future state, nor given queue timing by hand.
        assertRejectedBecause(scheduleGuard, "update test_runs set lifecycle_state = 'RUNNING' where run_id = ?", runId);
        assertRejectedBecause(
                scheduleGuard, "update test_runs set lifecycle_state = 'CLAIMED', run_version = 2 where run_id = ?",
                runId);
        assertRejectedBecause(scheduleGuard, "update test_runs set queued_at = now() where run_id = ?", runId);
        assertRejectedBecause(
                scheduleGuard, "update test_runs set run_version = run_version + 1 where run_id = ?", runId);
        assertRejectedBecause("test runs cannot be deleted", "delete from test_runs where run_id = ?", runId);

        assertThat(scheduler.schedule(created.organizationId(), runId, 1).disposition())
                .isEqualTo(ScheduleDisposition.SCHEDULED);

        // Once QUEUED, the next transitions are defined by the state machine but deliberately not implemented.
        assertRejectedBecause(
                scheduleGuard, "update test_runs set lifecycle_state = 'CLAIMED', run_version = 3 where run_id = ?",
                runId);
        assertRejectedBecause(
                scheduleGuard,
                "update test_runs set queue_deadline_at = now() + interval '1 day' where run_id = ?", runId);

        // The queue-time bundle is immutable, and the outbox delivery metadata cannot be forged yet.
        assertRejectedBecause(
                "execution attempts are immutable until claim is implemented",
                "update execution_attempts set attempt_state = 'CLAIMED' where run_id = ?", runId);
        assertRejectedBecause(
                "execution attempts are immutable until claim is implemented",
                "delete from execution_attempts where run_id = ?", runId);
        assertRejectedBecause(
                "execution dispatch identity and payload are immutable",
                "update execution_dispatches set run_version = 3 where run_id = ?", runId);
        assertRejectedBecause(
                "execution dispatch identity and payload are immutable",
                "update execution_dispatches set payload = '{}'::jsonb where run_id = ?", runId);
        assertRejectedBecause(
                "execution dispatch identity and payload are immutable",
                "delete from execution_dispatches where run_id = ?", runId);
        // V5 narrowed this guard rather than removing it: delivery state may move, semantic content may not, and
        // an unclaimed row can never be marked published.
        assertRejectedBecause(
                "only claim, release, publication, retry, terminal, suppression, and requeue transitions are supported",
                "update outbox_messages set published_at = now() where run_id = ?", runId);
        assertRejectedBecause(
                "only claim, release, publication, retry, terminal, suppression, and requeue transitions are supported",
                "update outbox_messages set payload_sha256 = repeat('a', 64) where run_id = ?", runId);
        assertRejectedBecause(
                "outbox messages are retained as delivery evidence",
                "delete from outbox_messages where run_id = ?", runId);
        assertRejectedBecause(
                "run lifecycle events are immutable",
                "update run_lifecycle_events set lifecycle_state = 'CLAIMED' where run_id = ?", runId);
        assertRejectedBecause(
                "run lifecycle events are immutable", "delete from run_lifecycle_events where run_id = ?", runId);

        // The sealed snapshot is still immutable.
        assertRejectedBecause(
                "run snapshots are immutable", "update run_snapshots set parallelism = 32 where run_id = ?", runId);

        // Row triggers do not fire for TRUNCATE, so the evidence tables are guarded at statement level too.
        for (String table : List.of(
                "test_runs", "run_snapshots", "execution_attempts", "execution_dispatches",
                "run_lifecycle_events", "outbox_messages")) {
            assertThatThrownBy(() -> jdbc.execute("truncate table " + table + " cascade"))
                    .as(table)
                    .hasMessageContaining("run scheduling evidence cannot be truncated");
        }

        // A second attempt cannot be added to a QUEUED run while infrastructure retry is out of scope. The per-row
        // guard rejects it first, because the run's current_attempt_id names attempt #1; uq_execution_attempts_one_
        // _per_run sits behind that guard as defence in depth rather than as the reachable rejection.
        assertRejectedBecause(
                "initial execution attempt requires its exact QUEUED run",
                """
                insert into execution_attempts
                    (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                     created_by, created_at)
                values (?, ?, ?, ?, 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now())
                """,
                UUID.randomUUID(), created.organizationId(), created.projectId(), runId);

        // Scheduling children cannot exist for a run that is not QUEUED. This one is rejected immediately by the
        // per-row guard, before the deferred bundle check is reached.
        CreatedRun orphan = createRun();
        assertRejectedBecause(
                "initial execution attempt requires its exact QUEUED run",
                """
                insert into execution_attempts
                    (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                     created_by, created_at)
                values (?, ?, ?, ?, 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now())
                """,
                UUID.randomUUID(), orphan.organizationId(), orphan.projectId(), orphan.runId());
        assertThat(count("execution_attempts", orphan.runId())).isZero();
    }

    @Test
    void theDatabaseRejectsAPayloadThatDoesNotExactlyMatchItsTrustedColumns() throws Exception {
        CreatedRun created = createRun();
        assertThat(scheduler.schedule(created.organizationId(), created.runId(), 1).disposition())
                .isEqualTo(ScheduleDisposition.SCHEDULED);
        Map<String, Object> row = jdbc.queryForMap(
                "select * from execution_dispatches where run_id = ?", created.runId());
        String canonical = String.valueOf(row.get("payload"));

        // Dropping a key while adding another keeps the field count at sixteen, so cardinality alone is not a guard.
        assertPayloadRejected(
                "execution dispatch payload must contain exactly the contract fields",
                row,
                canonical.replace("\"runSnapshotDigest\"", "\"evil\""));
        // A JSON null makes ->> yield SQL NULL. The guard must fail closed rather than let the NULL swallow the check.
        for (String field : List.of("runVersion", "payloadDigest", "queueDeadlineAt", "runSnapshotDigest", "runId")) {
            assertPayloadRejected(
                    "execution dispatch payload must exactly match its trusted semantic columns",
                    row,
                    nullOut(canonical, field));
        }
        // A string where the contract demands a number must not be silently coerced.
        assertPayloadRejected(
                "execution dispatch payload must exactly match its trusted semantic columns",
                row,
                canonical.replace("\"runVersion\":2", "\"runVersion\":\"2\""));
        // A wrong non-null value is rejected too, which is the case that already worked.
        assertPayloadRejected(
                "execution dispatch payload must exactly match its trusted semantic columns",
                row,
                canonical.replace(
                        "\"organizationId\":\"" + created.organizationId() + "\"",
                        "\"organizationId\":\"" + UUID.randomUUID() + "\""));
    }

    @Test
    void aQueuedRunCannotCommitWithoutItsCompleteCoupledBundle() throws Exception {
        CreatedRun created = createRun();
        var transactions = new TransactionTemplate(transactionManager);
        UUID attemptId = UUID.randomUUID();

        // Perform the legal transition and its attempt by hand, then omit the dispatch, lifecycle event, and outbox
        // row. Both statements individually satisfy their per-row guards, so only the deferred constraint triggers
        // can reject this — at COMMIT.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    Instant queuedAt = databaseNow();
                    jdbc.update(
                            """
                            update test_runs
                               set run_version = run_version + 1, lifecycle_state = 'QUEUED', queued_at = ?,
                                   queue_deadline_at = ?, current_attempt_id = ?, updated_by = 'kaas.scheduler',
                                   updated_at = ?
                             where run_id = ?
                            """,
                            java.sql.Timestamp.from(queuedAt),
                            java.sql.Timestamp.from(queuedAt.plusSeconds(300)),
                            attemptId,
                            java.sql.Timestamp.from(queuedAt),
                            created.runId());
                    jdbc.update(
                            """
                            insert into execution_attempts
                                (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                                 created_by, created_at)
                            values (?, ?, ?, ?, 1, 'WAITING_FOR_CLAIM', 'kaas.scheduler', ?)
                            """,
                            attemptId, created.organizationId(), created.projectId(), created.runId(),
                            java.sql.Timestamp.from(queuedAt));
                }))
                .hasMessageContaining("QUEUED run requires exactly one complete attempt dispatch event outbox bundle");

        // The whole partial bundle rolled back together.
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, created.runId()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                        "select run_version from test_runs where run_id = ?", Long.class, created.runId()))
                .isEqualTo(1L);
        assertThat(count("execution_attempts", created.runId())).isZero();
        // The run remains schedulable through the supported path.
        assertThat(scheduler.schedule(created.organizationId(), created.runId(), 1).disposition())
                .isEqualTo(ScheduleDisposition.SCHEDULED);
    }

    /** Replaces one field's value with JSON null, so that {@code ->>} yields SQL NULL inside the database guard. */
    private static String nullOut(String payload, String field) {
        int key = payload.indexOf("\"" + field + "\":");
        int valueStart = key + field.length() + 3;
        int valueEnd = payload.charAt(valueStart) == '"'
                ? payload.indexOf('"', valueStart + 1) + 1
                : indexOfAny(payload, valueStart, ',', '}');
        return payload.substring(0, valueStart) + "null" + payload.substring(valueEnd);
    }

    private static int indexOfAny(String value, int from, char first, char second) {
        for (int index = from; index < value.length(); index++) {
            if (value.charAt(index) == first || value.charAt(index) == second) {
                return index;
            }
        }
        throw new IllegalArgumentException("unterminated JSON value");
    }

    /**
     * Re-inserts the dispatch with a tampered payload under a fresh identity. Every trusted column keeps its
     * authoritative value, so only the payload guard can reject the row.
     */
    private void assertPayloadRejected(String reason, Map<String, Object> row, String tamperedPayload) {
        assertRejectedBecause(
                reason,
                """
                insert into execution_dispatches
                    (dispatch_id, message_id, organization_id, project_id, run_id, run_version,
                     attempt_id, attempt_number, run_snapshot_id, run_snapshot_sha256,
                     schema_version, message_type, producer, occurred_at, queue_deadline_at,
                     payload, payload_sha256)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), row.get("organization_id"), row.get("project_id"),
                row.get("run_id"), row.get("run_version"), row.get("attempt_id"), row.get("attempt_number"),
                row.get("run_snapshot_id"), row.get("run_snapshot_sha256"), row.get("schema_version"),
                row.get("message_type"), row.get("producer"), row.get("occurred_at"), row.get("queue_deadline_at"),
                tamperedPayload, row.get("payload_sha256"));
    }

    private void assertDispatchPayloadIsCanonicalAndCarriesNoExecutionAuthority(UUID runId) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("select * from execution_dispatches where run_id = ?", runId);
        String payload = String.valueOf(row.get("payload"));
        JsonNode node = objectMapper.readTree(payload);

        // The stored payload is exactly the published contract's field set, by name.
        assertThat(node.propertyNames())
                .containsExactlyInAnyOrder(
                        "schemaVersion", "messageId", "messageType", "dispatchId", "occurredAt", "producer",
                        "organizationId", "projectId", "runId", "runVersion", "attemptId", "attemptNumber",
                        "runSnapshotId", "runSnapshotDigest", "queueDeadlineAt", "payloadDigest");
        assertThat(node.get("schemaVersion").stringValue()).isEqualTo("1.0");
        assertThat(node.get("messageType").stringValue()).isEqualTo("EXECUTION_DISPATCH");
        assertThat(node.get("producer").stringValue()).isEqualTo("kaas.scheduler");
        assertThat(node.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(node.get("runVersion").asInt()).isEqualTo(2);
        assertThat(node.get("runId").stringValue()).isEqualTo(runId.toString());
        assertThat(node.get("runSnapshotId").stringValue()).isEqualTo(runId.toString());
        assertThat(node.get("runSnapshotDigest").stringValue()).matches("sha256:[a-f0-9]{64}");
        assertThat(node.get("payloadDigest").stringValue()).matches("sha256:[a-f0-9]{64}");

        // It is a transport intent, not an execution authority: no assignment, capability, source, or transport.
        String scannable = String.join(
                        " ",
                        String.join(" ", node.propertyNames()),
                        node.get("schemaVersion").stringValue(),
                        node.get("messageType").stringValue(),
                        node.get("producer").stringValue())
                .toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : CLAIM_TIME_AUTHORITY) {
            assertThat(scannable)
                    .as(forbidden)
                    .doesNotContain(forbidden.toLowerCase(java.util.Locale.ROOT));
        }

        // The digest is a versioned semantic digest, recomputable from the message rather than from byte order.
        ExecutionDispatch parsed = objectMapper.readValue(payload, ExecutionDispatch.class);
        String recomputed = ExecutionDispatchPolicy.digest(parsed);
        assertThat(recomputed).isEqualTo(parsed.payloadDigest());
        assertThat(recomputed).isEqualTo("sha256:" + row.get("payload_sha256"));
    }

    /**
     * Every guard in V3 and V4 raises SQLSTATE 23514, so asserting the exception type alone cannot tell which
     * constraint fired — or whether the intended one fired at all. Each rejection asserts its own reason.
     */
    private void assertRejectedBecause(String reason, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(reason);
    }

    private int count(String table, UUID runId) {
        return jdbc.queryForObject("select count(*) from " + table + " where run_id = ?", Integer.class, runId);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns"
                        + " where table_schema = 'public' and table_name = ?",
                String.class, table);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    /** Builds the full authorized input chain a run needs, then creates the CREATED run and its sealed snapshot. */
    private CreatedRun createRun() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token(organizationId, "scheduler-test");
        String projectId = createProject(bearer);

        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Scheduling feature",
                                "logicalPath", "features/scheduling.feature",
                                "source", "Feature: scheduling\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String secretId = json(post(
                        "/api/v1/projects/" + projectId + "/secret-references",
                        bearer,
                        key(),
                        json(Map.of("name", "schedulingClientSecret"))))
                .get("secretReferenceId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Scheduling environment",
                                "variables", List.of(variable("baseUrl", "STRING", "https://environment.example")),
                                "secretBindings", List.of(binding("clientSecret", secretId))))))
                .at("/initialRevision/revisionId")
                .stringValue();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Scheduling profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 2);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 300);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = json(post(
                        "/api/v1/projects/" + projectId + "/run-profiles", bearer, key(), json(profile)))
                .at("/initialRevision/revisionId")
                .stringValue();

        var response = post(
                "/api/v1/projects/" + projectId + "/runs",
                bearer,
                key(),
                json(Map.of(
                        "featureRevisionIds", List.of(featureRevision),
                        "runProfileRevisionId", profileRevision)));
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode run = json(response);
        return new CreatedRun(
                organizationId,
                UUID.fromString(projectId),
                UUID.fromString(run.get("runId").stringValue()),
                bearer,
                run);
    }

    private record CreatedRun(UUID organizationId, UUID projectId, UUID runId, String token, JsonNode run) {}

    private String createProject(String bearer) throws Exception {
        var response = post(
                "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID())));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("projectId").stringValue();
    }

    private HttpResponse<String> post(String path, String bearer, String idempotencyKey, String body)
            throws Exception {
        return send("POST", path, bearer, idempotencyKey, body);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, bearer, null, null);
    }

    private HttpResponse<String> send(
            String method, String path, String bearer, String idempotencyKey, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> variable(String key, String type, Object value) {
        return Map.of("key", key, "type", type, "value", value);
    }

    private static Map<String, Object> binding(String key, String secretReferenceId) {
        return Map.of("key", key, "secretReferenceId", secretReferenceId);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String token(UUID organizationId, String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("org_id", organizationId.toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean
        @Primary
        NimbusJwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
