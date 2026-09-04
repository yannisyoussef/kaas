package com.kaas.runner.execution;

import com.kaas.runner.command.ValidatedCommand;
import com.kaas.runner.sandbox.SandboxOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the result document for a synthetic execution.
 *
 * <p>It conforms to {@code runner-result.schema.json} — the existing contract, not a second format invented
 * for this slice. What it does not do is pretend the synthetic workload was a test suite.
 *
 * <p><strong>Zero features, and that is the honest number.</strong> The {@code features} array and the summary
 * counts describe the tenant's suite, and none of it ran: no feature source entered the sandbox. Populating
 * them with the workload's three shell assertions would attribute results to features that were never
 * executed, which is the same lie as reporting the engine as Karate, just further down the document. The
 * {@code producer} field is what carries the truth, and it says {@code kaas-runner-synthetic}.
 *
 * <p><strong>No artifact manifest.</strong> Nothing was retained, and there is no object store to have
 * retained it in. The contract makes the reference optional for exactly this reason — the alternative was
 * fabricating an {@code object-ref:} that any consumer would then try to fetch.
 */
public final class SyntheticResultDocument {

    private static final String SCHEMA_VERSION = "1.0";

    /**
     * Distinct from the engine's own producer name, permanently.
     *
     * <p>A consumer that cannot tell a synthetic execution from a real one will eventually treat one as the
     * other, and the direction that matters is a green synthetic run being read as a passing test suite.
     */
    private static final String PRODUCER = "kaas-runner-synthetic";

    private SyntheticResultDocument() {}

    public static String build(
            ObjectMapper mapper,
            ValidatedCommand command,
            SandboxOutcome outcome,
            Instant startedAt,
            Instant finishedAt,
            Duration provisioning,
            Duration reporting,
            UUID resultId,
            UUID messageId) {

        Map<String, String> observations = outcome.observations();
        String workloadOutcome = observations.get("workload_outcome");
        boolean passed = "PASSED".equals(workloadOutcome);

        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("messageId", messageId.toString());
        root.put("messageType", "EXECUTION_RESULT");
        root.put("occurredAt", finishedAt.toString());
        root.put("producer", PRODUCER);
        // The run correlates the whole lifecycle; the command is what caused this particular result.
        root.put("correlationId", command.runId().toString());
        root.put("causationId", command.commandId().toString());
        root.put("resultId", resultId.toString());
        root.put("commandId", command.commandId().toString());
        root.put("organizationId", command.organizationId().toString());
        root.put("projectId", command.projectId().toString());
        root.put("runId", command.runId().toString());
        root.put("runVersion", command.runVersion());
        root.put("attemptId", command.attemptId().toString());
        root.put("attemptNumber", command.attemptNumber());
        root.put("assignmentEpoch", command.assignmentEpoch());
        // These are the control plane's own instants, handed back rather than measured locally. The run's
        // execution_started_at is the authority the submission is checked against, and a locally measured
        // start would differ from it by however far this host's clock has drifted.
        root.put("startedAt", startedAt.toString());
        root.put("finishedAt", finishedAt.toString());
        root.put("testOutcome", passed ? "PASSED" : "FAILED");
        // SUCCEEDED regardless of the test outcome. The infrastructure did its job: it ran the workload and
        // collected the result. A failing test is not a failing execution.
        root.put("infrastructureOutcome", "SUCCEEDED");
        // Everything this execution produced was collected. COMPLETE is about collection, not about how much
        // of a suite ran — and the contract requires it whenever the infrastructure succeeded.
        root.put("resultCompleteness", "COMPLETE");

        ObjectNode timings = root.putObject("timings");
        timings.putObject("provisioning").put("durationMilliseconds", provisioning.toMillis());
        timings.putObject("execution").put("durationMilliseconds", outcome.elapsed().toMillis());
        timings.putObject("reporting").put("durationMilliseconds", reporting.toMillis());

        ObjectNode summary = root.putObject("summary");
        zeroCounts(summary.putObject("features"));
        zeroCounts(summary.putObject("scenarios"));
        zeroCounts(summary.putObject("steps"));

        root.putArray("features");
        return root.toString();
    }

    /**
     * All zeros, because no tenant feature, scenario, or step ran.
     *
     * <p>Written as its own method so the three call sites cannot drift into disagreeing, and so the intent is
     * stated once: these counts are about the tenant's suite, and this execution ran none of it.
     */
    private static void zeroCounts(ObjectNode counts) {
        counts.put("total", 0);
        counts.put("passed", 0);
        counts.put("failed", 0);
        counts.put("skipped", 0);
        counts.put("aborted", 0);
    }
}
