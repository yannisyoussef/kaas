package com.kaas.runner.daemon;

import com.kaas.runner.sandbox.EgressMetrics;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * The runner's operational metrics, in Prometheus text exposition (KAAS-DEPLOY-001).
 *
 * <h2>Cardinality is closed by construction</h2>
 *
 * <p>Every label value comes from a fixed vocabulary written in this file -- an execution status, a reason
 * code, an identity kind -- and anything else is refused at the call site. There is no method that accepts a run
 * id, a tenant, a worker id, a URL or an image name as a label, so a tenant cannot create a time series by
 * creating a run. That is a security property as much as an operational one: a metrics endpoint is scraped by
 * systems that were never meant to learn who the tenants are.
 *
 * <h2>What it reports about intake</h2>
 *
 * <p>The runner does not consume RabbitMQ (ADR-035), so nothing here says "consumer" or "broker". Intake is the
 * internal claim API, and its metrics say so: {@code claim_api_available}, polls, claims, empties, errors. The
 * control plane reports message transport separately; the two are different questions and are kept apart.
 *
 * <p>No dependency: the exposition format is lines of text, and the runner holds a Docker client -- every
 * library it adds runs next to that.
 */
public final class RunnerMetrics {
    /** Execution outcomes, exactly the statuses {@code ExecutionReport} can carry. */
    static final Set<String> EXECUTION_STATUSES =
            Set.of("COMPLETED", "REFUSED", "REJECTED", "INFRASTRUCTURE_FAILED", "AUTHORITY_LOST", "CRASHED");

    static final Set<String> IDENTITIES = Set.of("runner", "egress_proxy");

    static final Set<String> ATTESTATION_RESULTS = Set.of(
            "ACCEPTED", "REFUSED", "ASSESSMENT_FAILED", "SUBMISSION_FAILED");

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();
    private final Map<String, String> help = new ConcurrentHashMap<>();
    private volatile EgressMetrics egress;

    public RunnerMetrics() {
        describe("kaas_runner_claim_poll_total", "Long-poll waits sent to the internal claim API.");
        describe("kaas_runner_claim_attempt_total", "Claims sent to the internal claim API.");
        describe("kaas_runner_claim_success_total", "Claims that returned an assignment.");
        describe("kaas_runner_claim_empty_total", "Claims that found no delivered work.");
        describe("kaas_runner_claim_error_total", "Claims or waits that failed to reach or be answered by the API.");
        describe("kaas_runner_execution_started_total", "Assignments handed to the execution loop.");
        describe("kaas_runner_execution_completed_total", "Assignments the execution loop finished, by status.");
        describe("kaas_runner_reconcile_total", "Orphan reconciliation passes completed.");
        describe("kaas_runner_reconcile_failure_total", "Orphan reconciliation passes that failed.");
        describe("kaas_runner_orphans_reclaimed_total", "Abandoned managed containers and networks removed.");
        describe("kaas_runner_attestation_refresh_total", "Attestation refresh attempts, by result.");
        describe("kaas_runner_service_auth_refresh_total", "Service credentials obtained, by identity.");
        describe("kaas_runner_service_auth_refresh_failure_total", "Service credential refreshes that failed.");
    }

    // ---------------------------------------------------------------- intake

    void claimPolled() {
        increment("kaas_runner_claim_poll_total");
    }

    void claimAttempted() {
        increment("kaas_runner_claim_attempt_total");
    }

    void claimSucceeded() {
        increment("kaas_runner_claim_success_total");
    }

    void claimEmpty() {
        increment("kaas_runner_claim_empty_total");
    }

    void claimFailed() {
        increment("kaas_runner_claim_error_total");
    }

    // ---------------------------------------------------------------- execution

    void executionStarted() {
        increment("kaas_runner_execution_started_total");
    }

    void executionCompleted(String status) {
        increment("kaas_runner_execution_completed_total{status=\"" + closed(status, EXECUTION_STATUSES) + "\"}");
    }

    // ---------------------------------------------------------------- reconciliation

    void reconciled(int removed) {
        increment("kaas_runner_reconcile_total");
        add("kaas_runner_orphans_reclaimed_total", removed);
    }

    void reconcileFailed() {
        increment("kaas_runner_reconcile_failure_total");
    }

    // ---------------------------------------------------------------- attestation and identity

    void attestationRefreshed(String result) {
        increment("kaas_runner_attestation_refresh_total{result=\"" + closed(result, ATTESTATION_RESULTS) + "\"}");
    }

    void serviceAuthRefreshed(String identity) {
        increment("kaas_runner_service_auth_refresh_total{identity=\"" + closed(identity, IDENTITIES) + "\"}");
    }

    void serviceAuthRefreshFailed(String identity) {
        increment("kaas_runner_service_auth_refresh_failure_total{identity=\""
                + closed(identity, IDENTITIES) + "\"}");
    }

    // ---------------------------------------------------------------- gauges and egress

    /** A gauge whose name the caller fixes in code. Label-free by design. */
    void gauge(String name, String description, DoubleSupplier value) {
        if (!name.matches("kaas_runner_[a-z_]+")) {
            throw new IllegalArgumentException("gauge names are fixed, lower-case identifiers");
        }
        describe(name, description);
        gauges.put(name, value);
    }

    /** The egress counters the existing egress components already keep, exposed alongside these. */
    void includeEgress(EgressMetrics egressMetrics) {
        this.egress = egressMetrics;
    }

    /** A counter's current value, for tests and evidence. */
    public long count(String series) {
        AtomicLong counter = counters.get(series);
        return counter == null ? 0 : counter.get();
    }

    /** Prometheus text exposition format 0.0.4. */
    public String render() {
        StringBuilder out = new StringBuilder();
        Map<String, String> families = new TreeMap<>(help);
        Map<String, Long> counterSnapshot = new TreeMap<>();
        counters.forEach((series, value) -> counterSnapshot.put(series, value.get()));
        for (var family : families.entrySet()) {
            String name = family.getKey();
            boolean isGauge = gauges.containsKey(name);
            out.append("# HELP ").append(name).append(' ').append(family.getValue()).append('\n');
            out.append("# TYPE ").append(name).append(isGauge ? " gauge" : " counter").append('\n');
            if (isGauge) {
                out.append(name).append(' ').append(format(gauges.get(name).getAsDouble())).append('\n');
                continue;
            }
            boolean any = false;
            for (var series : counterSnapshot.entrySet()) {
                if (series.getKey().equals(name) || series.getKey().startsWith(name + "{")) {
                    out.append(series.getKey()).append(' ').append(series.getValue()).append('\n');
                    any = true;
                }
            }
            if (!any) {
                out.append(name).append(" 0\n");
            }
        }
        EgressMetrics egressMetrics = egress;
        if (egressMetrics != null) {
            // Already closed-vocabulary (EgressFailure names, resource kinds). Rendered as the egress components
            // name them, with the label syntax made exposition-valid.
            egressMetrics.snapshot().forEach((series, value) -> out
                    .append(series.replaceAll("\\{([a-z]+)=([A-Za-z_]+)}", "{$1=\"$2\"}"))
                    .append(' ').append(value).append('\n'));
        }
        return out.toString();
    }

    private void describe(String name, String description) {
        help.put(name, description);
    }

    private void increment(String series) {
        add(series, 1);
    }

    private void add(String series, long amount) {
        counters.computeIfAbsent(series, ignored -> new AtomicLong()).addAndGet(amount);
    }

    /** A label value from its closed set, or {@code OTHER}. Never the caller's string when it is not in the set. */
    private static String closed(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "OTHER";
    }

    private static String format(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value) ? Long.toString((long) value)
                : Double.toString(value);
    }
}
