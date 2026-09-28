#!/usr/bin/env python3
"""The KAAS-MSG-001 mutation battery: dispatch recovery after broker loss.

    python3 tests/mutation/recovery_mutants.py [--only M01,M05] [--list] [--ledger out.json]

Harness rules (permanent project rules):
  * a mutant's anchor must match EXACTLY once, and the applied diff is shown and must be non-empty;
  * the killing suite's previous results are deleted before it runs, so no stale report can kill a mutant;
  * the intended suite must have RUN: its XML must exist, be fresh, and have executed tests;
  * a mutant is killed only by an assertion FAILURE in the named suite -- a context that fails to load, a
    compile error or an unrelated setup error is reported as INVALID, never as killed;
  * every file is restored to its exact original bytes, always, and the worktree is compared with its starting
    state at the end.
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
API = "apps/api/src/main/java/com/kaas/api"
TEST = "apps/api/src/test/java/com/kaas/api"
REPO = f"{API}/outbox/infrastructure/JdbcDispatchRecoveryRepository.java"
SERVICE = f"{API}/outbox/application/DispatchRecovery.java"
CONSUMER = f"{API}/consumer/application/DispatchConsumptionService.java"
V15 = "apps/api/src/main/resources/db/migration/V15__dispatch_transport_recovery.sql"
DR = ":apps:api:deploymentReadinessTest"
RECOVERY = "com.kaas.api.DispatchRecoveryTests"
DEADLINE = "com.kaas.api.BrokerLossMeasurementTests"

PUBLISH = "            outcome = publisher.publish(message);"


def publish_with(expression):
    return f"            outcome = publisher.publish({expression});"


def with_payload(expression):
    return ("new com.kaas.api.outbox.domain.OutboxMessage(message.outboxId(), message.messageId(), "
            "message.messageType(), message.schemaVersion(), message.organizationId(), message.projectId(), "
            f"message.runId(), message.dispatchId(), {expression}, message.payloadDigest(), message.occurredAt(), "
            "message.publishAttempts())")


WAITING_QUEUED = "             where r.lifecycle_state = 'QUEUED'\n"
WHERE_TRUE = "             where true\n"
WAITING_ATTEMPT = "               and a.attempt_state = 'WAITING_FOR_CLAIM'\n"
WAITING_DEADLINE = "               and r.queue_deadline_at > clock_timestamp()\n"
WAITING_INBOX = "               and not exists (select 1 from dispatch_inbox i\n"
WAITING_INBOX_FULL = ("               and not exists (select 1 from dispatch_inbox i\n"
                      "                                where i.consumer = ? and i.message_id = o.message_id)\n")
# Placeholders are KEPT when a predicate is removed, so the mutated SQL still binds and runs: a mutant killed by a
# bind error would be killed for a reason that has nothing to do with the defect it names.
KEEP_BIND = "               and cast(? as text) is not null\n"
RECHECK_DELIVERED = """                         when exists (select 1 from dispatch_inbox i
                                       where i.consumer = ? and i.message_id = o.message_id) then 'DELIVERED'
"""
RECHECK_QUEUED = "                         when r.lifecycle_state <> 'QUEUED' then 'RUN_NOT_QUEUED'\n"
RECHECK_ATTEMPT = "                         when a.attempt_state <> 'WAITING_FOR_CLAIM' then 'ATTEMPT_NOT_WAITING'\n"
RECHECK_DEADLINE = "                         when r.queue_deadline_at <= clock_timestamp() then 'QUEUE_DEADLINE_PASSED'\n"

# (id, description, [(file, find, replace), ...], kind, task, tests, killer)
#   kind "test": killed by an assertion failure in `killer` (a class simple name) within `tests`
#   kind "evidence": killed when the evidence gate refuses what the suite recorded
MUTANTS = [
    ("M01", "recovered message gets a new messageId",
     [(SERVICE, PUBLISH, publish_with(
         "new com.kaas.api.outbox.domain.OutboxMessage(message.outboxId(), UUID.randomUUID(), "
         "message.messageType(), message.schemaVersion(), message.organizationId(), message.projectId(), "
         "message.runId(), message.dispatchId(), message.payload(), message.payloadDigest(), message.occurredAt(), "
         "message.publishAttempts())"))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M02", "recovered message gets a new dispatchId",
     [(SERVICE, PUBLISH, publish_with(with_payload(
         "message.payload().replace(message.dispatchId().toString(), UUID.randomUUID().toString())")))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M03", "recovered payload changes",
     [(SERVICE, PUBLISH, publish_with(with_payload("message.payload() + \" \"")))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M04", "recovery mutates the ExecutionDispatch",
     [(REPO, "    public boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt) {\n",
       "    public boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt) {\n"
       "        jdbc.update(\"update execution_dispatches set occurred_at = occurred_at where message_id = ?\", messageId);\n")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M05", "recovery works by resetting published_at to null",
     [(REPO, "        UUID[] ids = claimed.stream()",
       "        jdbc.update(\"update outbox_messages set published_at = null where message_id = any(?)\",\n"
       "                (Object) claimed.stream().map(row -> (UUID) row.get(\"message_id\")).toArray(UUID[]::new));\n"
       "        UUID[] ids = claimed.stream()")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M06", "a delivered dispatch is considered recoverable",
     [(REPO, WAITING_INBOX_FULL, KEEP_BIND),
      (REPO, RECHECK_DELIVERED, "                         when cast(? as text) is null then 'DELIVERED'\n")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M07", "a claimed run is considered recoverable",
     [(REPO, WAITING_QUEUED, WHERE_TRUE), (REPO, WAITING_ATTEMPT, ""),
      (REPO, RECHECK_QUEUED, ""), (REPO, RECHECK_ATTEMPT, "")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M08", "a cancelled run is considered recoverable",
     [(REPO, WAITING_QUEUED, WHERE_TRUE), (REPO, RECHECK_QUEUED, "")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M09", "a terminal (expired) run is considered recoverable",
     [(REPO, WAITING_QUEUED, WHERE_TRUE), (REPO, WAITING_DEADLINE, ""), (REPO, RECHECK_QUEUED, ""),
      (REPO, RECHECK_DEADLINE, "")],
     "test", DR, [DEADLINE], "BrokerLossMeasurementTests"),
    ("M10", "a run past its deadline is considered recoverable",
     [(REPO, WAITING_DEADLINE, ""), (REPO, RECHECK_DEADLINE, "")],
     "test", DR, [DEADLINE], "BrokerLossMeasurementTests"),
    ("M11", "grace period removed",
     [(REPO, "        long graceMillis = grace.toMillis();", "        long graceMillis = 0;")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M12", "recovery claim/lease removed",
     [(REPO, "                          and (x.claim_id is null or x.claim_expires_at <= clock_timestamp())\n", "")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M13", "republication spacing and cap removed: an unbounded duplicate storm",
     [(SERVICE, "    Duration interval(int publications) {\n        Duration interval = grace;",
       "    Duration interval(int publications) {\n        if (true) {\n            return Duration.ZERO;\n        }\n        Duration interval = grace;"),
      (REPO, "                          and x.recovery_publications < ?\n", "                          and x.recovery_publications < ? + 1000\n")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M14", "a broker confirm is not required before success is recorded",
     [(SERVICE, "        if (outcome.status() != PublishStatus.CONFIRMED) {", "        if (false) {")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M15", "success is recorded before the publish",
     [(SERVICE, "        PublishOutcome outcome;\n        try {\n            outcome = publisher.publish(message);",
       "        recoveries.recordPublished(message.messageId(), claimId, recoveries.currentDatabaseTime().plus(grace));\n"
       "        PublishOutcome outcome;\n        try {\n            outcome = publisher.publish(message);")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M16", "a crash after the confirm loses all future recovery",
     [(REPO, "                          and (x.claim_id is null or x.claim_expires_at <= clock_timestamp())",
       "                          and (x.claim_id is null)")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M17", "a recovery failure hot-loops",
     [(SERVICE, "    Duration backoff(int attempts) {\n        Duration backoff = baseBackoff;",
       "    Duration backoff(int attempts) {\n        if (true) {\n            return Duration.ZERO;\n        }\n        Duration backoff = baseBackoff;")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M18", "the queue deadline no longer wins the race",
     [(REPO, RECHECK_DEADLINE, "")],
     "test", DR, [DEADLINE], "BrokerLossMeasurementTests"),
    ("M19", "cancellation no longer wins the race",
     [(REPO, RECHECK_QUEUED, "")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M20", "transport recovery creates attempt #2",
     [(SERVICE, PUBLISH, publish_with(with_payload(
         "message.payload().replace(\"\\\"attemptNumber\\\": 1\", \"\\\"attemptNumber\\\": 2\")"
         ".replace(\"\\\"attemptNumber\\\":1\", \"\\\"attemptNumber\\\":2\")")))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M21", "transport recovery carries an assignment epoch",
     [(SERVICE, PUBLISH, publish_with(with_payload("message.payload().replaceFirst(\"\\\\{\", \"{\\\"assignmentEpoch\\\": 2, \")")))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M22", "consumer duplicate detection removed",
     [(CONSUMER, "        if (existing.isPresent()) {\n            InboxRecord decided = existing.orElseThrow();",
       "        if (false) {\n            InboxRecord decided = existing.orElseThrow();")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M23", "a semantic digest conflict is accepted",
     [(CONSUMER, "            if (!decided.matches(digest)) {", "            if (false) {")],
     "test", ":apps:api:test", ["com.kaas.api.DispatchConsumerInboxTests"], "DispatchConsumerInboxTests"),
    ("M24", "a missing inbox marker suppresses recovery",
     [(REPO, WAITING_INBOX, "               and exists (select 1 from dispatch_inbox i\n")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M25", "the inbox-retention assumption is broken",
     [(V15, "CREATE TABLE dispatch_recoveries (",
       "DROP TRIGGER dispatch_inbox_guard ON dispatch_inbox;\n\nCREATE TABLE dispatch_recoveries (")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M26", "production does not run recovery",
     [("apps/api/src/main/resources/application-production.properties",
       "kaas.dispatch.recovery.enabled=${KAAS_DISPATCH_RECOVERY_ENABLED:true}",
       "kaas.dispatch.recovery.enabled=${KAAS_DISPATCH_RECOVERY_ENABLED:false}")],
     "test", DR, ["com.kaas.api.deployment.MigrationModeTests"], "MigrationModeTests"),
    ("M27", "an unpublished outbox row enters the recovery path",
     [(REPO, "               and o.published_at is not null\n", ""),
      (REPO, "                   and o.published_at + (? * interval '1 millisecond') <= clock_timestamp()\n",
       "                   and coalesce(o.published_at, o.occurred_at) + (? * interval '1 millisecond') <= clock_timestamp()\n"),
      (REPO, "                          and greatest(o.published_at, coalesce(x.last_recovered_at, o.published_at))\n",
       "                          and greatest(coalesce(o.published_at, o.occurred_at), coalesce(x.last_recovered_at, o.published_at, o.occurred_at))\n"),
      (REPO, "                         when o.message_type <> 'EXECUTION_DISPATCH' or o.published_at is null\n",
       "                         when o.message_type <> 'EXECUTION_DISPATCH'\n"),
      (V15, "                          AND o.published_at IS NOT NULL AND o.terminal_disposition IS NULL) THEN",
       "                          AND o.terminal_disposition IS NULL) THEN")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M28", "recovery consults RabbitMQ state",
     [(SERVICE, "    private final DispatchRecoveryRepository recoveries;",
       "    private final DispatchRecoveryRepository recoveries;\n    private org.springframework.amqp.core.AmqpAdmin brokerDepth;")],
     "test", ":apps:api:test", ["com.kaas.api.ControlPlaneArchitectureTest"], "ControlPlaneArchitectureTest"),
    ("M29", "metrics use a run identity as a label",
     [(SERVICE, "        count(\"kaas.dispatch.recovery.published\", null, null);",
       "        count(\"kaas.dispatch.recovery.published\", \"runId\", message.runId().toString());")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M30", "the broker-wipe test passes without an initial message existing",
     [(f"{API}/outbox/application/OutboxRelay.java", "    public int drainOnce() {",
       "    public int drainOnce() {\n        if (true) {\n            return 0;\n        }")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M31", "the broker-wipe test passes without the message being destroyed",
     [(f"{TEST}/DispatchRecoveryTests.java", "        Message original = rabbit.receive(queue, 5_000);\n        assertThat(original).isNotNull();",
       "        Message original = rabbit.receive(queue, 5_000);\n        assertThat(original).isNotNull();\n"
       "        rabbit.send(\"\", queue, original);")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M32", "the run succeeds through the original copy, not the recovery",
     [(SERVICE, "        if (outcome.status() != PublishStatus.CONFIRMED) {",
       "        outcome = PublishOutcome.confirmed();\n        if (outcome.status() != PublishStatus.CONFIRMED) {"),
      (SERVICE, PUBLISH, "            outcome = PublishOutcome.confirmed();")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M33", "the stable-identity assertion is removed while identity breaks",
     [(f"{TEST}/DispatchRecoveryTests.java",
       "        assertThat(r.getMessageId()).isEqualTo(o.getMessageId()).isEqualTo(outboxBefore.get(\"message_id\").toString());\n",
       ""),
      (SERVICE, PUBLISH, publish_with(
         "new com.kaas.api.outbox.domain.OutboxMessage(message.outboxId(), UUID.randomUUID(), "
         "message.messageType(), message.schemaVersion(), message.organizationId(), message.projectId(), "
         "message.runId(), message.dispatchId(), message.payload(), message.payloadDigest(), message.occurredAt(), "
         "message.publishAttempts())"))],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M34", "the duplicate race causes two runner claims",
     [(f"{API}/consumer/application/WorkerAssignmentService.java",
       "            if (outcome.disposition() == ClaimDisposition.CLAIMED) {",
       "            if (outcome.disposition() == ClaimDisposition.CLAIMED\n                    || outcome.disposition() == ClaimDisposition.ALREADY_CLAIMED) {"),
      (f"{API}/consumer/infrastructure/JdbcWorkerAssignmentRepository.java",
       "                           and r.lifecycle_state = 'QUEUED'\n                           and r.current_attempt_id = d.attempt_id\n                           and a.attempt_state = 'WAITING_FOR_CLAIM'\n                           and r.queue_deadline_at > clock_timestamp()\n                         order by r.queued_at, r.run_id\n                         limit 1\n                           for update of r skip locked",
       "                           and r.current_attempt_id = d.attempt_id\n                         order by r.queued_at, r.run_id\n                         limit 1")],
     "test", DR, [RECOVERY], "DispatchRecoveryTests"),
    ("M35", "a terminal run is resurrected by a delayed recovered message",
     [(f"{API}/controlplane/application/RunClaimService.java",
       "        if (previous.lifecycleState() != RunLifecycle.QUEUED) {", "        if (false) {"),
      (f"{API}/controlplane/application/RunClaimService.java",
       "        if (at.isAfter(previous.queueDeadlineAt())) {", "        if (false) {"),
      # Without this the run-version check refuses the reaped run on its own (RUN_VERSION_MOVED) and the mutant
      # would be masked by defence in depth rather than representing a resurrection.
      (f"{API}/controlplane/application/RunClaimService.java",
       "        if (previous.runVersion() != expectedRunVersion) {", "        if (false) {")],
     "test", DR, [DEADLINE], "BrokerLossMeasurementTests"),
]



MAIN = "aPublishedDispatchTheBrokerLosesIsRepublishedAsTheSameDispatchAndDeliveredOnce"
CRASH = "aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce"
FRESH = "aFreshInstanceResumesRecoveryFromPostgresAfterTheOldOneDied"
LATE_COPY = "aRepublishedCopyThatArrivesAfterTheDeadlineIsRefusedAndTheRunIsNotResurrected"

# For each mutant: the tests that must fail for the INTENDED reason, and -- only where the intended protection IS a
# database guard -- the guard's own refusal text, which then counts as that reason. Nothing else counts: an
# unrelated failure, a bind error or a context that did not load is INVALID, never a kill.
EXPECT = {
    "M01": ([MAIN], []),
    "M02": ([MAIN], []),
    "M03": ([MAIN, "aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution"], []),
    "M04": ([MAIN], ["execution dispatch identity and payload are immutable"]),
    "M05": ([MAIN], ["only claim, release, publication, retry, terminal, suppression, and requeue transitions"]),
    "M06": (["aDeliveredButUnclaimedRunIsNeverRepublished"], []),
    "M07": (["aRunAWorkerOwnsIsNeverRepublishedEvenWithNoInboxRecord"], []),
    "M08": (["aCancelledRunIsNeverResurrected"], []),
    "M09": ([LATE_COPY, "anExpiredRunIsNeverEligible"], []),
    "M10": (["anExpiredRunIsNeverEligible"], []),
    "M11": ([MAIN], []),
    "M12": ([CRASH, FRESH], []),
    "M13": (["twoRecoveryInstancesRacingForOneLostDispatchPublishItOnce",
             "republicationIsSpacedAndBoundedWhileNothingConsumes"], []),
    "M14": (["aBrokerOutageDuringRecoveryBacksOffAndNeverTerminatesTheRun"], []),
    "M15": ([MAIN, CRASH], []),
    "M16": ([CRASH, FRESH], []),
    "M17": (["aBrokerOutageDuringRecoveryBacksOffAndNeverTerminatesTheRun"], []),
    "M18": (["aDeadlineThatPassesBetweenTheRecoveryClaimAndItsPublicationWins"], []),
    "M19": (["cancellationThatCommitsBeforeTheRepublishWinsAndNothingIsPublished"], []),
    "M20": ([MAIN], []),
    "M21": ([MAIN], []),
    "M22": (["aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution", CRASH],
            ["uq_dispatch_inbox_message"]),
    "M23": (["aKnownIdentityCarryingDifferentBytesIsAnIntegrityConflictAndNotADuplicate"], []),
    "M24": ([MAIN], []),
    "M25": (["recoveryHistoryAndTheDeliveredMarkerAreRetainedAndOnlyGrow"], []),
    "M26": (["theProductionProfileTurnsMigrationOffAndCannotBeTalkedOutOfIt",
             "theProductionManagementPortServesHealthDeploymentAndMetricsAndNothingElse"], []),
    "M27": (["aDispatchTheRelayNeverPublishedIsTheRelaysNotRecoverys"], []),
    "M28": (["dispatchRecoveryDecidesFromPostgresAloneAndNeverFromBrokerState"], []),
    "M29": (["recoveryMetricsCarryNoIdentity"], []),
    "M30": ([MAIN], []),
    "M31": ([MAIN], []),
    "M32": ([MAIN], []),
    "M33": ([MAIN], []),
    "M34": (["aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution"], []),
    "M35": ([LATE_COPY], []),
}

# Errors that are a behaviour, not a setup failure: a condition that never became true.
BEHAVIOURAL_ERRORS = ("org.awaitility.core.ConditionTimeoutException",)


def run(command, cwd=ROOT):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True)


def results_dir(task):
    project, name = task.rsplit(":", 1)
    return os.path.join(ROOT, project.strip(":").replace(":", "/"), "build/test-results", name)


def worktree_state():
    return run(["git", "status", "--porcelain"]).stdout


def apply(edits):
    originals = {}
    for path, find, replace in edits:
        full = os.path.join(ROOT, path)
        if full not in originals:
            originals[full] = open(full, "rb").read()
        text = open(full, encoding="utf-8").read()
        if text.count(find) != 1:
            restore(originals)
            raise SystemExit(f"anchor not found exactly once in {path}: {find[:70]!r}")
        open(full, "w", encoding="utf-8").write(text.replace(find, replace))
    for full, original in originals.items():
        if open(full, "rb").read() == original:
            restore(originals)
            raise SystemExit(f"mutation did not change {full}")
    return originals


def restore(originals):
    for full, original in originals.items():
        open(full, "wb").write(original)


def suite_result(directory, killer, started):
    path = next(iter(glob.glob(os.path.join(directory, f"TEST-*{killer}.xml"))), None)
    if path is None or os.path.getmtime(path) < started:
        return None
    root = ET.parse(path).getroot()
    failures, errors = [], []
    for case in root.iter("testcase"):
        if case.find("failure") is not None:
            failures.append(case.get("name"))
        elif case.find("error") is not None:
            error = case.find("error")
            errors.append((case.get("name"), error.get("type") or "",
                           (error.get("message") or "") + "\n" + (error.text or "")[:20000]))
    return {"tests": int(root.get("tests", "0")), "failures": failures, "errors": errors}


def kill(mutant):
    identifier, description, edits, kind, task, tests, killer = mutant
    originals = apply(edits)
    diff = run(["git", "diff", "--stat", "--"] + [os.path.relpath(p, ROOT) for p in originals]).stdout.strip()
    try:
        directory = results_dir(task)
        shutil.rmtree(directory, ignore_errors=True)
        started = time.time()
        command = ["./gradlew", "-q", task, "--no-daemon", "--rerun"]
        for test in tests:
            command += ["--tests", test]
        build = run(command)
        if kind == "evidence":
            evidence = os.path.join(ROOT, "apps/api/build/evidence/deploymentReadinessTest/dispatch-recovery.properties")
            if not os.path.exists(evidence) or os.path.getmtime(evidence) < started:
                return "INVALID", ["the suite wrote no fresh evidence"], diff
            gate = run(["python3", ".github/scripts/evidence-gate.py", evidence, f"{killer}=true"])
            return ("KILLED" if gate.returncode != 0 else "SURVIVED"), [gate.stdout.strip()], diff
        result = suite_result(directory, killer, started)
        if result is None:
            reason = "the intended suite did not run" + (" (compile failure)" if "error:" in build.stderr + build.stdout else "")
            return "INVALID", [reason], diff
        if result["tests"] == 0:
            return "INVALID", ["the intended suite executed no tests"], diff
        expected, guards = EXPECT[identifier]
        name = lambda method: method.split("(")[0]
        kills = [f"{name(t)} (assertion)" for t in result["failures"] if name(t) in expected]
        for method, kind_, text in result["errors"]:
            if name(method) not in expected:
                continue
            if kind_ in BEHAVIOURAL_ERRORS:
                kills.append(f"{name(method)} (timeout)")
            elif any(guard in text for guard in guards):
                kills.append(f"{name(method)} (guard refused)")
        if kills:
            return "KILLED", kills[:4], diff
        unexpected = [name(t) for t in result["failures"]] + [name(m) + " (error " + k.rsplit(".", 1)[-1] + ")"
                                                               for m, k, _ in result["errors"]]
        if unexpected:
            return "INVALID", ["failed only for other reasons: " + ", ".join(unexpected[:4])], diff
        return "SURVIVED", [], diff
    finally:
        restore(originals)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--ledger")
    parser.add_argument("--check-anchors", action="store_true")
    arguments = parser.parse_args()
    if arguments.list:
        for mutant in MUTANTS:
            print(f"{mutant[0]}  {mutant[1]}  -> {mutant[6]}")
        return 0
    if arguments.check_anchors:
        for mutant in MUTANTS:
            restore(apply(mutant[2]))
        print(f"all {len(MUTANTS)} mutants apply cleanly")
        return 0
    before = worktree_state()
    selected = [m for m in MUTANTS if not arguments.only or m[0] in arguments.only.split(",")]
    ledger, bad = [], []
    for mutant in selected:
        status, evidence, diff = kill(mutant)
        print(f"{mutant[0]} {status:8} {mutant[1]} :: {'; '.join(evidence)}", flush=True)
        ledger.append({"id": mutant[0], "mutant": mutant[1], "status": status, "evidence": evidence, "diff": diff})
        if status != "KILLED":
            bad.append(mutant[0])
    after = worktree_state()
    if after != before:
        print("WORKTREE CHANGED BY THE BATTERY:\n" + after)
        bad.append("WORKTREE")
    if arguments.ledger:
        json.dump(ledger, open(arguments.ledger, "w"), indent=2)
    if bad:
        print("NOT KILLED: " + ",".join(bad))
        return 1
    print(f"all {len(selected)} mutants killed; worktree restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
