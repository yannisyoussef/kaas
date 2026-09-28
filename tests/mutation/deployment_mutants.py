#!/usr/bin/env python3
"""The KAAS-DEPLOY-001 mutation battery.

Each mutant is one exact textual change to production code (or build/config) that reintroduces a specific
deployment defect. For each: apply it, run only the tests that must kill it, require that a NAMED test failed
(read from the JUnit XML, never from console text), then restore the file byte for byte -- always, including on
interrupt.

    python3 tests/mutation/deployment_mutants.py [--only D05,D08] [--list]

A mutant "survives" when every named test still passes; that is a gap in the gate and exits non-zero. Mutants
whose killing evidence needs the mediating runtime are marked CI and are not run here; the ledger in
KAAS_DEPLOYMENT_READINESS_REPORT.md records which CI step kills each.
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

API = "apps/api/src/main/java/com/kaas/api"
RUNNER = "services/runner/src/main/java/com/kaas/runner"

# (id, description, file, find, replace, gradle task, test filter(s), killer test class prefix)
MUTANTS = [
    ("D01", "production composition omits the ExecutionLoop",
     f"{RUNNER}/daemon/RunnerComposition.java",
     "                loop::execute,",
     "                (runId, attemptId, epoch) -> ExecutionLoop.ExecutionReport.completed(\"PASSED\"),",
     ":services:runner:test", ["com.kaas.runner.daemon.ProductionRunnerCompositionTests"],
     "ProductionRunnerCompositionTests"),
    ("D02", "claim poller never starts",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "            scheduleMaintenance();\n            intake.start();",
     "            scheduleMaintenance();",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D03", "runner reports READY without runsc",
     f"{RUNNER}/daemon/RuntimePreflight.java",
     "            readiness.set(Readiness.Condition.RUNTIME, false, \"RUNTIME_NOT_REGISTERED\");\n            return Optional.empty();",
     "            readiness.holds(Readiness.Condition.RUNTIME);\n            return Optional.of(\"sha256:\" + \"0\".repeat(64));",
     ":services:runner:test", ["com.kaas.runner.daemon.RuntimePreflightTests"], "RuntimePreflightTests"),
    ("D04", "runner falls back to runc when runsc is absent",
     f"{RUNNER}/daemon/RuntimePreflight.java",
     "            registered = RuntimeImplementation.registeredPath(docker, name);",
     "            try { registered = RuntimeImplementation.registeredPath(docker, name); }\n"
     "            catch (RuntimeException absent) { name = \"runc\"; registered = RuntimeImplementation.registeredPath(docker, name); }",
     ":services:runner:test", ["com.kaas.runner.daemon.RuntimePreflightTests"], "RuntimePreflightTests"),
    ("D05", "startup reconciliation removed",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "            while (!reconcile()) {\n                backoff = pause(backoff);\n            }\n",
     "",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D06", "reconciler deletes resources it does not own",
     f"{RUNNER}/sandbox/OrphanSandboxReconciler.java",
     "                .withLabelFilter(Map.of(SandboxLabels.MANAGED, \"true\"))\n",
     "",
     ":services:runner:test", ["com.kaas.runner.sandbox.OrphanReconciliationTests"], "OrphanReconciliationTests"),
    ("D07", "periodic reconciliation removed",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "        maintenance.scheduleWithFixedDelay(this::reconcile, reconcile, reconcile, TimeUnit.MILLISECONDS);",
     "",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D08", "shutdown still performs new claims",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "        readiness.drain();\n        synchronized (intakeLock) {\n            if (!claiming) {\n                intake.interrupt();\n            }\n        }",
     "        synchronized (intakeLock) {\n        }",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D09", "worker id accepted from the request body",
     f"{API}/internal/WorkerAssignmentController.java",
     "        if (!onlyFields(request, Set.of())) {\n            return unknownField();\n        }\n        String worker = authentication.getName();",
     "        String worker = request != null && request.get(\"workerId\") instanceof String chosen\n"
     "                ? chosen : authentication.getName();",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.WorkerClaimEndpointTests"], "WorkerClaimEndpointTests"),
    ("D10", "a claim reports success for a run somebody already owns",
     f"{API}/consumer/application/WorkerAssignmentService.java",
     "            if (outcome.disposition() == ClaimDisposition.CLAIMED) {",
     "            if (outcome.disposition() == ClaimDisposition.CLAIMED\n"
     "                    || outcome.disposition() == ClaimDisposition.ALREADY_CLAIMED) {",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.WorkerClaimEndpointTests"], "WorkerClaimEndpointTests",
     # ...and the candidate query offers runs somebody already owns, unlocked -- together, the defect the
     # contention and second-runner tests exist to catch. Either edit alone is not a double claim.
     [(f"{API}/consumer/infrastructure/JdbcWorkerAssignmentRepository.java",
       "                           and r.lifecycle_state = 'QUEUED'\n                           and r.current_attempt_id = d.attempt_id\n                           and a.attempt_state = 'WAITING_FOR_CLAIM'\n                           and r.queue_deadline_at > clock_timestamp()\n                         order by r.queued_at, r.run_id\n                         limit 1\n                           for update of r skip locked",
       "                           and r.current_attempt_id = d.attempt_id\n                         order by r.queued_at, r.run_id\n                         limit 1")]),
    ("D11", "claim bypasses RunClaimService",
     f"{API}/consumer/application/WorkerAssignmentService.java",
     "            var outcome = claims.claim(dispatch, workerId);",
     "            var outcome = new RunClaimService.ClaimOutcome(ClaimDisposition.CLAIMED, \"CLAIMED\", null);",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.WorkerClaimEndpointTests"], "WorkerClaimEndpointTests"),
    ("D12", "claim loses the assignment epoch",
     f"{API}/internal/WorkerAssignmentController.java",
     "        body.put(\"assignmentEpoch\", assignment.assignmentEpoch());",
     "        body.put(\"assignmentEpoch\", 0);",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.WorkerClaimEndpointTests"], "WorkerClaimEndpointTests"),
    ("D13", "runner acquires a RabbitMQ client",
     "services/runner/build.gradle.kts",
     "    implementation(\"tools.jackson.core:jackson-databind:3.1.5\")",
     "    implementation(\"tools.jackson.core:jackson-databind:3.1.5\")\n    implementation(\"com.rabbitmq:amqp-client:5.30.0\")",
     ":services:runner:verifyLauncherHasNoUserContentDependencies", [], "BUILD"),
    ("D14", "migrator starts the full API",
     f"{API}/deployment/DatabaseMigrator.java",
     "    public static void main(String[] arguments) {\n        System.exit(run(System.getenv(), System.out));",
     "    public static void main(String[] arguments) {\n        org.springframework.boot.SpringApplication.run(com.kaas.api.KaasApiApplication.class, arguments);\n        System.exit(run(System.getenv(), System.out));",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.deployment.MigrationModeTests"], "MigrationModeTests"),
    ("D15", "API production startup auto-runs Flyway",
     "apps/api/src/main/resources/application-production.properties",
     "spring.flyway.enabled=false",
     "spring.flyway.enabled=true",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.deployment.MigrationModeTests"], "MigrationModeTests"),
    ("D16", "attestation refresh removed",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "        scheduleRefresh(parts.configuration().attestationRefreshInterval());\n    }",
     "    }",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D17", "expired attestation accepted",
     f"{API}/execution/application/AttestationSubmissionService.java",
     "        if (unusable.isPresent()) {",
     "        if (unusable.isPresent() && unusable.orElseThrow() != AttestationVerification.STALE) {",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.AttestationSubmissionTests"], "AttestationSubmissionTests"),
    ("D18", "runtime change not re-measured",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "            if (measured.isPresent() && !parts.attestation().runtimeMatches(measured.orElseThrow())) {",
     "            if (false) {",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D19", "service token never refreshes",
     f"{RUNNER}/daemon/ServiceIdentity.java",
     "        if (current == null || !now.isBefore(refreshAt)) {",
     "        if (current == null) {",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D20", "expired service token accepted",
     f"{RUNNER}/daemon/ServiceIdentity.java",
     "        if (!now.isBefore(next.expiresAt().minus(EXPIRY_SKEW))) {\n            lastFailure = \"ISSUED_EXPIRED\";",
     "        if (false) {\n            lastFailure = \"ISSUED_EXPIRED\";",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D21", "engine image tag accepted",
     f"{RUNNER}/daemon/RunnerConfiguration.java",
     "        String engine = reader.image(\"KAAS_RUNNER_ENGINE_IMAGE\");",
     "        String engine = reader.required(\"KAAS_RUNNER_ENGINE_IMAGE\");",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerConfigurationTests", "com.kaas.runner.RunnerApplicationTest"],
     "RunnerConfigurationTests"),
    ("D22", "proxy image tag accepted",
     f"{RUNNER}/daemon/RunnerConfiguration.java",
     "            String proxy = reader.image(\"KAAS_RUNNER_EGRESS_PROXY_IMAGE\");",
     "            String proxy = reader.required(\"KAAS_RUNNER_EGRESS_PROXY_IMAGE\");",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerConfigurationTests"], "RunnerConfigurationTests"),
    ("D24", "malformed release manifest accepted",
     "packages/api-contracts/release-manifest.schema.json",
     "@sha256:[0-9a-f]{64}$\"",
     "(@sha256:[0-9a-f]{64})?$\"",
     "NODE", [], "validate-schemas"),
    ("D25", "runner ready before reconciliation",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "            while (!reconcile()) {\n                backoff = pause(backoff);\n            }\n            readiness.holds(Readiness.Condition.STARTUP_RECONCILIATION);",
     "            readiness.holds(Readiness.Condition.STARTUP_RECONCILIATION);\n            maintenance.execute(this::reconcile);",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D26", "broker loss reported as recovered",
     "apps/api/src/test/java/com/kaas/api/BrokerLossMeasurementTests.java",
     "\"rabbitmq_loss_recovery\", \"false\"",
     "\"rabbitmq_loss_recovery\", \"true\"",
     "EVIDENCE", ["com.kaas.api.BrokerLossMeasurementTests"], "evidence-gate"),
    ("D27", "queue-deadline terminal transition removed",
     f"{API}/controlplane/application/QueueDeadlineReaper.java",
     "    public int reapExpired() {",
     "    public int reapExpired() {\n        if (true) {\n            return 0;\n        }",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.BrokerLossMeasurementTests"], "BrokerLossMeasurementTests"),
    ("D28", "active resource reaped",
     f"{RUNNER}/sandbox/OrphanSandboxReconciler.java",
     "            if (created == null || created > abandonedBefore) {",
     "            if (created == null) {",
     ":services:runner:test", ["com.kaas.runner.sandbox.OrphanReconciliationTests"], "OrphanReconciliationTests"),
    ("D29", "startup orphan survives",
     f"{RUNNER}/sandbox/OrphanSandboxReconciler.java",
     "        int removed = reconcileContainers();\n        removed += reconcileNetworks();\n        return removed;",
     "        return 0;",
     ":services:runner:test", ["com.kaas.runner.sandbox.OrphanReconciliationTests"], "OrphanReconciliationTests"),
    ("D30", "a run identity becomes a metric label",
     f"{RUNNER}/daemon/RunnerMetrics.java",
     "        increment(\"kaas_runner_execution_completed_total{status=\\\"\" + closed(status, EXECUTION_STATUSES) + \"\\\"}\");",
     "        increment(\"kaas_runner_execution_completed_total{status=\\\"\" + status + \"\\\"}\");",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests",
     # paired edit: pass the run id where the status was
     (f"{RUNNER}/daemon/RunnerDaemon.java", "                metrics.executionCompleted(status);",
      "                metrics.executionCompleted(claimed.runId() + \"/\" + status);")),
    ("D31", "deployment check passes without a runner",
     f"{API}/deployment/DeploymentStatusService.java",
     "                && \"UP\".equals(brokerState)\n                && ready > 0;",
     "                && \"UP\".equals(brokerState);",
     ":apps:api:deploymentReadinessTest", ["com.kaas.api.deployment.DeploymentStatusTests"], "DeploymentStatusTests"),
    ("D33", "runner background work survives shutdown",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "        maintenance.shutdownNow();\n        refreshes.shutdownNow();",
     "",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D34", "long poll prevents timely shutdown",
     f"{RUNNER}/daemon/RunnerDaemon.java",
     "            if (!claiming) {\n                intake.interrupt();\n            }",
     "",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerDaemonLifecycleTests"], "RunnerDaemonLifecycleTests"),
    ("D35", "runner requires a RabbitMQ credential",
     f"{RUNNER}/daemon/RunnerConfiguration.java",
     "        Credentials credentials = reader.credentials(\"KAAS_RUNNER\");",
     "        Credentials credentials = reader.credentials(\"KAAS_RUNNER\");\n        reader.required(\"KAAS_RUNNER_RABBITMQ_URL\");",
     ":services:runner:test", ["com.kaas.runner.daemon.RunnerConfigurationTests", "com.kaas.runner.daemon.RunnerDaemonLifecycleTests"],
     "RunnerConfigurationTests"),
]

# Killed only where the mediating runtime or a registry exists: recorded in the report, run by CI.
CI_ONLY = {
    "D23": "revision label missing -- deployment-readiness 'Build, push and verify a release manifest' (--check-labels)",
    "D32": "image revision mismatch accepted -- deployment-readiness 'Confirm the verifier refuses what it must' (relabelled)",
}


def run(command, cwd=ROOT):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True)


def failed_tests(results_dir, prefix):
    failures = []
    for path in glob.glob(os.path.join(ROOT, results_dir, "TEST-*.xml")):
        name = os.path.basename(path)[len("TEST-"):-len(".xml")]
        if prefix not in name:
            continue
        for case in ET.parse(path).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(f"{name.rsplit('.', 1)[-1]}.{case.get('name')}")
    return failures


def results_dir(task):
    project, name = task.rsplit(":", 1)
    module = project.strip(":").replace(":", "/")
    return f"{module}/build/test-results/{name}"


def apply(path, find, replace):
    full = os.path.join(ROOT, path)
    original = open(full, encoding="utf-8").read()
    if original.count(find) != 1:
        raise SystemExit(f"mutation anchor not found exactly once in {path}: {find[:60]!r}")
    open(full, "w", encoding="utf-8").write(original.replace(find, replace))
    return full, original


def kill(mutant):
    identifier, description, path, find, replace, task, tests, killer = mutant[:8]
    paired = mutant[8] if len(mutant) > 8 else []
    if isinstance(paired, tuple):
        paired = [paired]
    applied = [apply(path, find, replace)]
    try:
        for edit in paired:
            applied.append(apply(*edit))
        if task == "NODE":
            result = run(["node", "scripts/validate-schemas.mjs"], cwd=os.path.join(ROOT, "packages/api-contracts"))
            return result.returncode != 0, ["validate-schemas refused the fixtures"] if result.returncode else []
        if task == "EVIDENCE":
            run(["./gradlew", "-q", ":apps:api:deploymentReadinessTest", "--tests", tests[0], "--no-daemon"])
            evidence = "apps/api/build/evidence/deploymentReadinessTest/broker-loss.properties"
            result = run(["python3", ".github/scripts/evidence-gate.py", evidence, "rabbitmq_loss_recovery=false"])
            return result.returncode != 0, [result.stdout.strip()]
        command = ["./gradlew", "-q", task, "--no-daemon", "--rerun-tasks" if task.endswith("verifyLauncherHasNoUserContentDependencies") else "--rerun"]
        for test in tests:
            command += ["--tests", test]
        result = run(command)
        if killer == "BUILD":
            return result.returncode != 0 and "amqp-client" in (result.stdout + result.stderr), [
                line for line in (result.stdout + result.stderr).splitlines() if "Forbidden" in line][:1]
        failures = failed_tests(results_dir(task), killer)
        return bool(failures), failures
    finally:
        for full, original in reversed(applied):
            open(full, "w", encoding="utf-8").write(original)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--ledger")
    arguments = parser.parse_args()
    selected = [m for m in MUTANTS if not arguments.only or m[0] in arguments.only.split(",")]
    if arguments.list:
        for mutant in MUTANTS:
            print(f"{mutant[0]}  {mutant[1]}")
        for identifier, where in CI_ONLY.items():
            print(f"{identifier}  CI: {where}")
        return 0
    ledger = []
    survivors = []
    for mutant in selected:
        killed, evidence = kill(mutant)
        status = "KILLED" if killed else "SURVIVED"
        print(f"{mutant[0]} {status:8} {mutant[1]} :: {'; '.join(evidence[:3])}", flush=True)
        ledger.append({"id": mutant[0], "mutant": mutant[1], "status": status, "killedBy": evidence[:5]})
        if not killed:
            survivors.append(mutant[0])
    if arguments.ledger:
        json.dump(ledger, open(arguments.ledger, "w"), indent=2)
    if survivors:
        print("SURVIVORS: " + ",".join(survivors))
        return 1
    print(f"all {len(selected)} mutants killed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
