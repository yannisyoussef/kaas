package com.kaas.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * What the post-deploy check reads (KAAS-DEPLOY-001): READY only when a runner is really there.
 *
 * <p>Against a real database and a real broker, so {@code database}, {@code schema} and {@code broker} are all UP
 * and the only thing varying is the runner. "A runner process exists" is not enough; it must have asked for work
 * recently AND hold evidence fresh enough to be authorized.
 */
@Testcontainers
@SpringBootTest(properties = {
    "kaas.scheduling.auto.enabled=false",
    "kaas.reaping.auto.enabled=false",
    "kaas.outbox.relay.enabled=false",
    "kaas.consumer.enabled=false",
    "kaas.claim.reconcile.enabled=false",
    "kaas.execution.reconcile.enabled=false",
    "kaas.execution.attestation-max-age=PT24H"
})
class DeploymentStatusTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-deployment-status");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private DeploymentStatusService status;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        jdbc.update("alter table sandbox_attestations disable trigger all");
        try {
            jdbc.update("delete from sandbox_attestations");
        } finally {
            jdbc.update("alter table sandbox_attestations enable trigger all");
        }
        jdbc.update("delete from worker_presence");
    }

    @Test
    void withNoRunnerThePlatformIsNotReadyEvenThoughEverythingElseIsUp() {
        Map<String, Object> report = status.status();

        assertThat(report).containsEntry("database", "UP").containsEntry("schema", "CURRENT")
                .containsEntry("broker", "UP").containsEntry("runnersPolling", 0)
                .containsEntry("runnersWithCurrentEvidence", 0).containsEntry("status", "NOT_READY");
    }

    @Test
    void aRunnerThatPollsButHoldsNoEvidenceIsNotEnough() {
        present("kaas.worker.no-evidence", Duration.ZERO);

        assertThat(status.status()).containsEntry("runnersPolling", 1)
                .containsEntry("runnersWithCurrentEvidence", 0).containsEntry("status", "NOT_READY");
    }

    @Test
    void staleEvidenceIsNotEnough() {
        present("kaas.worker.stale", Duration.ZERO);
        evidence("kaas.worker.stale", Duration.ofHours(25));

        assertThat(status.status()).containsEntry("runnersWithCurrentEvidence", 0).containsEntry("status", "NOT_READY");
    }

    @Test
    void evidenceFromARunnerThatStoppedPollingIsNotEnough() {
        present("kaas.worker.gone", Duration.ofMinutes(10));
        evidence("kaas.worker.gone", Duration.ofMinutes(5));

        assertThat(status.status()).containsEntry("runnersPolling", 0).containsEntry("status", "NOT_READY");
    }

    @Test
    void aPresentRunnerWithCurrentEvidenceMakesItReady() {
        present("kaas.worker.here", Duration.ZERO);
        evidence("kaas.worker.here", Duration.ofMinutes(5));

        assertThat(status.status()).containsEntry("runnersPolling", 1)
                .containsEntry("runnersWithCurrentEvidence", 1).containsEntry("status", "READY");
        // Status words and counts only: nothing names the worker, a host or a key.
        assertThat(status.status().toString()).doesNotContain("kaas.worker.here");
    }

    private void present(String worker, Duration ago) {
        jdbc.update("insert into worker_presence (worker_id, first_seen_at, last_seen_at) values (?, ?, ?)",
                worker, Timestamp.from(Instant.now().minus(ago).minusSeconds(1)), Timestamp.from(Instant.now().minus(ago)));
    }

    private void evidence(String worker, Duration age) {
        Instant assessed = Instant.now().minus(age);
        jdbc.update("""
                insert into sandbox_attestations (submission_id, worker_id, attestation_id, key_id, payload_digest,
                    runtime_subject, runtime_implementation_digest, assessed_at, received_at, document)
                values (?, ?, ?, 'k', ?, 's', ?, ?, ?, '{}')
                """,
                UUID.randomUUID(), worker, "a-" + UUID.randomUUID(), "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), Timestamp.from(assessed), Timestamp.from(assessed));
    }
}
