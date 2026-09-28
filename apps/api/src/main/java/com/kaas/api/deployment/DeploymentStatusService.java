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

    public DeploymentStatusService(
            SubmittedAttestationRepository attestations,
            ObjectProvider<ConnectionFactory> broker,
            ObjectProvider<Flyway> migrations,
            javax.sql.DataSource dataSource,
            SecretTransit secrets,
            @Value("${kaas.execution.attestation-max-age}") Duration attestationMaxAge) {
        this.attestations = attestations;
        this.broker = broker;
        this.migrations = migrations;
        this.dataSource = dataSource;
        this.secrets = secrets;
        this.attestationMaxAge = attestationMaxAge;
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
        boolean operational = "UP".equals(database)
                && "CURRENT".equals(schema)
                && "UP".equals(brokerState)
                && ready > 0;
        report.put("status", operational ? "READY" : "NOT_READY");
        report.put("database", database);
        report.put("schema", schema);
        report.put("broker", brokerState);
        report.put("runnersPolling", seen);
        report.put("runnersWithCurrentEvidence", ready);
        report.put("secretProvider", secrets.state().name());
        return report;
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
