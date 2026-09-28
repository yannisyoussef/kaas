package com.kaas.api.deployment;

import com.kaas.api.execution.application.SubmittedAttestationRepository;
import com.kaas.api.secrets.domain.SecretTransit;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Whether this deployment's platform base is operational: the answer the post-deploy check reads.
 *
 * <p>Deliberately NOT a synthetic execution. Running a tenant workload to prove a deployment would need a tenant,
 * would write run history, and would make the check depend on a secret provider for no reason. What a deployment
 * has to prove is that each thing an execution will need is present and reachable, and this reads each one
 * directly:
 *
 * <ul>
 *   <li>{@code database} -- a query on the application's own datasource;
 *   <li>{@code schema} -- no migration pending (the migrator ran, and this build matches it);
 *   <li>{@code broker} -- a connection the dispatch relay would publish on is open;
 *   <li>{@code runners} -- at least one worker asked for work recently AND holds evidence fresh enough to be
 *       authorized. "A runner process exists" is not enough: one without current evidence can claim nothing it
 *       could run.
 * </ul>
 *
 * <p>The secret provider is reported and is NOT part of the verdict. A sealed Vault blocks secret-bearing runs
 * and nothing else (ADR-034); a deployment whose base is healthy must not read as failed because of it, and one
 * whose base is broken must not read as healthy because Vault is fine.
 *
 * <p>Everything reported is a status word or a count. No host, URL, credential, key id, worker id or tenant
 * identity leaves this class.
 */
@Service
public class DeploymentStatusService {
    /** A worker that has not asked for work within this long is not counted as present. */
    static final Duration PRESENCE_WINDOW = Duration.ofSeconds(90);

    private final SubmittedAttestationRepository attestations;
    private final ObjectProvider<ConnectionFactory> broker;
    private final ObjectProvider<Flyway> migrations;
    private final javax.sql.DataSource dataSource;
    private final SecretTransit secrets;
    private final Duration attestationMaxAge;
    private final com.kaas.api.outbox.application.DispatchRecovery recovery;
    private final Duration recoveryStallAfter;
    private final Instant startedAt = Instant.now();

    public DeploymentStatusService(
            SubmittedAttestationRepository attestations,
            ObjectProvider<ConnectionFactory> broker,
            ObjectProvider<Flyway> migrations,
            javax.sql.DataSource dataSource,
            SecretTransit secrets,
            @Value("${kaas.execution.attestation-max-age}") Duration attestationMaxAge,
            com.kaas.api.outbox.application.DispatchRecovery recovery,
            @Value("${kaas.dispatch.recovery.interval}") Duration recoveryInterval,
            @Value("${kaas.dispatch.recovery.initial-delay}") Duration recoveryInitialDelay,
            @Value("${kaas.dispatch.recovery.claim-ttl}") Duration recoveryClaimTtl) {
        this.attestations = attestations;
        this.broker = broker;
        this.migrations = migrations;
        this.dataSource = dataSource;
        this.secrets = secrets;
        this.attestationMaxAge = attestationMaxAge;
        this.recovery = recovery;
        // Three missed ticks, plus the longest a single pass may legitimately take (one lease), plus the initial
        // delay the first pass waits. Past that with no completed pass, the recovery loop is not running.
        this.recoveryStallAfter = recoveryInterval.multipliedBy(3).plus(recoveryClaimTtl).plus(recoveryInitialDelay);
    }

    public Map<String, Object> status() {
        Map<String, Object> report = new LinkedHashMap<>();
        String database;
        Instant now = null;
        try {
            now = attestations.currentDatabaseTime();
            database = "UP";
        } catch (RuntimeException unavailable) {
            database = "DOWN";
        }
        String schema = "UP".equals(database) ? schema() : "UNKNOWN";
        String brokerState = brokerState();
        int seen = 0;
        int ready = 0;
        if (now != null) {
            try {
                seen = attestations.workersSeenSince(now.minus(PRESENCE_WINDOW));
                ready = attestations.workersSeenWithEvidence(now.minus(PRESENCE_WINDOW), now.minus(attestationMaxAge));
            } catch (RuntimeException unavailable) {
                database = "DOWN";
            }
        }
        String recoveryState = recoveryState(now);
        boolean operational = "UP".equals(database)
                && "CURRENT".equals(schema)
                && "UP".equals(brokerState)
                && ready > 0
                // A dead recovery loop means a dispatch the broker loses would be lost again. Dispatches merely
                // WAITING for recovery are not a failure and do not affect the verdict.
                && ("DISABLED".equals(recoveryState) || "UP".equals(recoveryState) || "STARTING".equals(recoveryState));
        report.put("status", operational ? "READY" : "NOT_READY");
        report.put("database", database);
        report.put("schema", schema);
        report.put("broker", brokerState);
        report.put("runnersPolling", seen);
        report.put("runnersWithCurrentEvidence", ready);
        report.put("dispatchRecovery", recoveryState);
        report.put("dispatchesAwaitingRecovery", now == null ? -1 : recovery.eligibleNow());
        report.put("secretProvider", secrets.state().name());
        return report;
    }

    /**
     * DISABLED, STARTING (no pass yet, still inside the initial window), UP (a pass completed recently), or STALLED.
     * Judged on this instance: recovery runs in every API instance, so a stalled one is this one.
     */
    private String recoveryState(Instant databaseNow) {
        if (!recovery.enabled()) {
            return "DISABLED";
        }
        var last = recovery.lastPass();
        if (last.isEmpty()) {
            return Duration.between(startedAt, Instant.now()).compareTo(recoveryStallAfter) > 0 ? "STALLED" : "STARTING";
        }
        Instant reference = databaseNow != null ? databaseNow : Instant.now();
        return Duration.between(last.orElseThrow(), reference).compareTo(recoveryStallAfter) > 0 ? "STALLED" : "UP";
    }

    private String schema() {
        try {
            Flyway flyway = migrations.getIfAvailable(() -> SchemaGuard.flywayFor(dataSource));
            return flyway.info().pending().length == 0 ? "CURRENT" : "PENDING";
        } catch (RuntimeException unreadable) {
            return "UNKNOWN";
        }
    }

    private String brokerState() {
        ConnectionFactory factory = broker.getIfAvailable();
        if (factory == null) {
            return "UNCONFIGURED";
        }
        try (Connection connection = factory.createConnection()) {
            return connection.isOpen() ? "UP" : "DOWN";
        } catch (RuntimeException unreachable) {
            return "DOWN";
        }
    }
}
