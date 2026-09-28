# KAAS-MSG-001 — Durable dispatch recovery after broker loss: slice report

PostgreSQL-authoritative redrive, stable dispatch identity, bounded recovery, duplicate-safe republication, and
closure of the KAAS-DEPLOY-001 deployment blocker. Decided by
[ADR-036](docs/adr/036-postgres-authoritative-dispatch-reconstruction.md); architecture in
[dispatch-recovery.md](docs/architecture/dispatch-recovery.md).

## 1. Executive summary

KAAS-DEPLOY-001 proved against a real broker that a dispatch RabbitMQ loses after publication was never rebuilt:
the run sat `QUEUED` until its queue deadline and ended `TIMED_OUT` (`rabbitmq_loss_recovery=false`). This slice
makes PostgreSQL reconstruct it. A published dispatch whose run is still `QUEUED`, unclaimed and within its
deadline, and which the API consumer has no durable record of, is republished after a grace period — **the same
dispatch**: same message id, same bytes, same headers, same publisher. The consumer inbox, keyed by message id
and semantic digest since the consumer slice, decides every duplicate once. Recovery is at least once, leased in
the database, spaced and capped, and the queue deadline still ends anything recovery cannot save.

Proved end to end in CI: a real broker wipe, reconstruction from PostgreSQL, admission by the API consumer, a
claim by the production runner over HTTP, and Karate under gVisor to `COMPLETED/PASSED`.

## 2. Starting commit

`907247f42ee13784e444ef9311e7bbf59aa6490c` on `codex/project-feature-control-plane`, equal to upstream, clean
worktree, no stash; final KAAS-DEPLOY-001 CI run 36458572062, 11/11. No part of KAAS-MSG-001 had been started.

## 3. Existing blocker

```
published (broker-confirmed) ─▶ lost before consumption ─▶ no reconstruction ─▶ QUEUED ─▶ QUEUE_DEADLINE ─▶ TIMED_OUT
published_before_loss=true  broker_depth 1→0  republished_after_loss=0  claims_after_loss=0  rabbitmq_loss_recovery=false
```

## 4. Decision

The user chose application-side durable reconstruction; the Ops backup assumption was not changed. PostgreSQL is
authoritative; RabbitMQ is transport. The runner is untouched: it still waits and claims over the internal API and
has no broker dependency.

## 5. Threat / failure model

| Failure | Handled by |
|---|---|
| broker loses a confirmed message before consumption | recovery republishes it (§13) |
| message merely delayed past the grace | recovery republishes; both copies decided once (§19) |
| two API instances recover at once | database lease, `SKIP LOCKED` (§16, §29) |
| recovery dies after claim / after confirm / after record | lease expiry; duplicates absorbed (§18) |
| broker down during recovery | backoff, failure category, no terminalization (§27) |
| cancellation or deadline races a republication | re-check before publish; consumer revalidates (§22–23) |
| a republication that changes the message | same outbox row, re-verified; M01–M03, M20–M21 |
| recovery forgets across restarts | all state in PostgreSQL (§28) |

## 6. Existing outbox semantics

Scheduling writes an immutable `execution_dispatches` row and an `outbox_messages` row in one transaction. The
relay claims unpublished rows (`FOR UPDATE SKIP LOCKED`, lease), publishes with mandatory routing and a correlated
confirm, and records `published_at` only after the confirm. `published_at` is one-way: the outbox guard allows
only claim, release, publication, retry, terminal, suppression and requeue transitions, and publication is final.
Before this slice nothing ever looked at a published row again.

## 7. Existing inbox semantics

The consumer takes a per-message advisory lock, reads `dispatch_inbox (consumer, message_id)`, and on a hit counts
a redelivery and returns the recorded disposition (a different digest is a `CONFLICT`, never a second decision).
Its decision commits before the broker is acknowledged. The inbox guard refuses `DELETE` and `TRUNCATE`; only
redelivery accounting may change a row.

## 8. Recovery alternatives

A — recovery columns on `outbox_messages`: rejected, it reopens a closed guard and overloads the publication
timestamps. B — one recovery row per dispatch: chosen. C — append-only attempt log plus state: rejected, more than
the diagnostics need. See ADR-036 §5.

## 9. Selected state model

`dispatch_recoveries`: one row per dispatch recovery has considered, created lazily when a published dispatch
first becomes eligible; attempts, confirmed republications, first/last recovery, last attempt and failure
category, next eligible instant, lease.

## 10. Schema changes

V15, expand-only: one table, its guard trigger and truncate refusal, one index. No change to any existing table,
trigger or index; the previous release never reads the table (Flyway treats V15 as a future migration for it).
The guard admits a row only for a published execution dispatch, unattempted and unclaimed; identity is immutable;
counters grow by at most one per write; a republication cannot be recorded without an attempt, and an attempt only
as its claim is released; history fields change only with an attempt; `DELETE`/`TRUNCATE` refused.

## 11. Immutable dispatch identity

Neither `execution_dispatches` nor `outbox_messages` is written by recovery. `dispatchId`, `messageId`, run,
attempt, attempt number, snapshot id and digest, payload, payload digest and queue deadline are the persisted
ones, by construction and by assertion (§34).

## 12. Initial publication

Unchanged: the relay. A dispatch the relay never published — or terminally disposed — is not eligible for
recovery and remains under the relay's retry and dead-letter policy (M27).

## 13. Recovery publication

The outbox row, read back byte for byte, verified again by the relay's verifier (which re-derives the semantic
digest and checks message, tenant and dispatch identity), and published through the same `DispatchPublisher`:
same message id, correlation id, timestamp, persistent mode, headers, exchange, routing key, mandatory, confirm.

## 14. Eligibility

Published `EXECUTION_DISPATCH`, not terminally disposed; run `QUEUED`; its current attempt `WAITING_FOR_CLAIM`;
deadline in the future; no inbox row for the message; grace elapsed since the last publication; spacing elapsed;
under the publication and attempt caps; lease free or expired. Driven from queued runs, every step indexed.

## 15. Grace period

`kaas.dispatch.recovery.grace`, default 30 s, validated shorter than half the queue timeout. An engineering margin
for a consumer restart or short backlog, not a measurement.

## 16. Recovery lease

Claimed in one short transaction with `FOR UPDATE … SKIP LOCKED`; the lease is taken on the statement's clock and
returned, and the publisher stays inside it (checked before the re-check and again immediately before
publishing). Every outcome write is conditional on still holding the claim. Validated: lease ≥ batch × confirm
timeout; lease + grace < queue timeout.

## 17. Publisher confirm

Success is recorded only after `PublishStatus.CONFIRMED` (mandatory routing, correlated confirm). A nack, return,
timeout or broker error is a recorded failure with a closed category.

## 18. Crash windows

After claim, before publish: lease expires, republished later. After confirm, before record: republished after
the lease — a duplicate the consumer absorbs (tested). After record: next republication, if any, waits longer. No
path records success before the confirm.

## 19. Delayed-original race

The original is left in the queue past the grace; recovery republishes; both copies are consumed: one inbox
decision, delivery count 2, duplicate counter +1, one successful claim, the second claimant gets nothing, two
lifecycle events (scheduled, claimed).

## 20. Duplicate semantics

Same message id and semantic digest ⇒ the recorded decision is returned and only `delivery_count` moves. A
duplicate with different bytes would be a `CONFLICT` and could not arise from recovery, which publishes the
persisted bytes.

## 21. Inbox / delivery marker

`dispatch_inbox` is the durable fact that the consumer saw the message, of any disposition. Retention is asserted
(delete refused) because recovery now relies on it (M25). A delivered-but-unclaimed run is never republished (M06).

## 22. Queue deadline precedence

Expired runs are never eligible (M10); a deadline that passes between claim and publish aborts the publish (M18);
a copy arriving after the reaper ended the run is refused `STALE` and nothing is claimed (M35); a lost dispatch
recovery cannot repair still ends `TIMED_OUT / QUEUE_DEADLINE`.

## 23. Cancellation precedence

A cancelled run is never eligible (M08); cancellation after the claim and before publish aborts it (M19); a copy
that escaped just before cancellation is refused `STALE`.

## 24. Attempt semantics

No new attempt: the first one keeps waiting for claim. Asserted: one attempt row after recovery, and the claimed
attempt is the dispatch's own (M20).

## 25. Assignment semantics

No epoch is consumed by transport; the first claim after recovery is epoch 1 (M21).

## 26. Retry / backoff

Successful republications are spaced grace, 2×grace, 4×… capped at `max-interval`, at most `max-publications`;
failures back off from `base-backoff` to `max-backoff`. Twenty passes against a failing broker make one attempt
(M17).

## 27. Broker outage

A failed republication records its category and backs off; the run stays `QUEUED`; when the broker returns the
next due pass delivers. The deadline remains the bound.

## 28. Startup recovery

No in-memory schedule: every pass starts from PostgreSQL, and a fresh instance continues a dead instance's claim
once its lease expires (M16, M26).

## 29. Multi-instance behavior

Two instances released at a barrier publish one copy; repeatedly hammering both publishes nothing more within the
spacing (M12, M13).

## 30. Metrics

`kaas.dispatch.recovery.eligible` and `.capped` (gauges), `.claimed`, `.published`, `.failed{reason}` (closed:
the publisher's failure codes), `.skipped{reason}` (closed), `.claim_lost`; duplicates at the consumer are
`kaas.dispatch.duplicate{disposition}`. No identity labels (M29).

## 31. Logging

Structured events `DISPATCH_RECOVERED`, `DISPATCH_RECOVERY_PUBLISH_FAILED`, `DISPATCH_RECOVERY_FAILED`,
`DISPATCH_RECOVERY_PASS_FAILED` with opaque ids and categories; never a payload, source, secret or capability.

## 32. Migration compatibility

Expand-only V15; the full API suite, including `MigrationUpgradeTests` over populated previous-version databases,
passes; the production boot as `kaas_app` passes; the migrator applies V15 in the shipped image.

## 33. Broker-wipe E2E

`DeploymentPipelineTests` (runsc, CI run 36491575187): with the API consumer paused, a run was published and
confirmed, the broker was observed holding it (depth 1), and it was purged (depth 0) before any consumer decision
and before any recovery. The consumer resumed; nothing was driven from the test. The recovery timer republished
it (`recovery_publications=1`), the API consumer admitted it once (`consumer_deliveries=1`) under the same message
id and semantic digest, after the purge, the production runner claimed it over HTTP (`execution_attempts=1`,
epoch 1), and Karate executed it under gVisor: `run_outcome=COMPLETED/PASSED`, `sandbox_runtime=runsc`,
`rabbitmq_loss_recovery=true`. The ordinary run in the same suite needed no recovery (`ordinary_run_recovered=false`).

## 34. Stable identity evidence

`DispatchRecoveryTests` takes the original message off the broker (destroying it, unseen by the consumer), then
compares the recovered one: message id, body bytes, `payloadDigest`/`messageType`/`schemaVersion` headers,
content type, correlation id, persistent delivery, routing key, and the body's dispatch id, message id, run,
attempt, attempt number, snapshot id and digest, queue deadline and payload digest — all equal. The dispatch row
and outbox row are unchanged; `published_at` is the first publication.

## 35. Duplicate-race evidence

`duplicate_race_decisions=1 duplicate_race_deliveries=2 duplicate_race_claims=1`.

## 36. Deadline-race evidence

`deadline_race_published_after_deadline=false deadline_race_resurrected=false`;
`late_recovered_copy_consumer=STALE late_recovered_copy_claims=0 late_recovered_copy_resurrected=false`;
unrecoverable loss: `recovery_attempted=true recovery_publish_confirmed=false terminal_outcome=TIMED_OUT/QUEUE_DEADLINE queue_deadline_fail_closed=true`.

## 37. Cancellation-race evidence

`cancellation_before_republish_published=false cancellation_after_republish_consumer=STALE cancelled_run_resurrected=false`.

## 38. Crash-recovery evidence

`crash_after_confirm_republished=true crash_after_confirm_decisions=1 crash_after_confirm_claims=1`.

## 39. Security review

No new endpoint, credential, route or principal. The runner is unchanged and still has no broker path. Recovery
republishes only bytes the relay already published, verified again; the consumer's strict validation is unchanged
(an epoch or unknown field injected into a recovered copy is refused, M21). No tenant data in logs or labels.

## 40. Mutation evidence

`tests/mutation/recovery_mutants.py`, strict harness (§41). **All 35 killed for their intended reason.** Two needed a
second look, both recorded: M04's first run was INVALID (a transient initialisation error, which the harness
refused to count as a kill) and it was killed on rerun; M18 SURVIVED the first strict run because a test's short
lease masked the property it claimed to prove — the test was fixed, then M09, M10, M18 and M35 were re-run and
killed. An earlier, non-strict version of the harness had counted any error as a kill; the independent review
caught it, and none of its results are reported here.

| ID | Mutant | Result | Killed by (test, reason) |
|---|---|---|---|
| M01 | recovered message gets a new messageId | KILLED | main broker-wipe test (assertion) |
| M02 | recovered message gets a new dispatchId | KILLED | main broker-wipe test (assertion) |
| M03 | recovered payload changes | KILLED | main broker-wipe test (assertion); aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution (assertion) |
| M04 | recovery mutates the ExecutionDispatch | KILLED (rerun) | first run INVALID (`initializationError`, a transient class-initialisation failure the strict harness refused to count); rerun alone: aPublishedDispatch…DeliveredOnce (assertion) — the unchanged dispatch guard refuses the write and recovery fails visibly |
| M05 | recovery works by resetting published_at to null | KILLED | main broker-wipe test (assertion) |
| M06 | a delivered dispatch is considered recoverable | KILLED | aDeliveredButUnclaimedRunIsNeverRepublished (assertion) |
| M07 | a claimed run is considered recoverable | KILLED | aRunAWorkerOwnsIsNeverRepublishedEvenWithNoInboxRecord (assertion) |
| M08 | a cancelled run is considered recoverable | KILLED | aCancelledRunIsNeverResurrected (assertion) |
| M09 | a terminal (expired) run is considered recoverable | KILLED | anExpiredRunIsNeverEligible (assertion) |
| M10 | a run past its deadline is considered recoverable | KILLED | anExpiredRunIsNeverEligible (assertion) |
| M11 | grace period removed | KILLED | main broker-wipe test (assertion) |
| M12 | recovery claim/lease removed | KILLED | aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce (assertion); aFreshInstanceResumesRecoveryFromPostgresAfterTheOldOneDied (assertion) |
| M13 | republication spacing and cap removed: an unbounded duplicate storm | KILLED | republicationIsSpacedAndBoundedWhileNothingConsumes (assertion) |
| M14 | a broker confirm is not required before success is recorded | KILLED | aBrokerOutageDuringRecoveryBacksOffAndNeverTerminatesTheRun (assertion) |
| M15 | success is recorded before the publish | KILLED | aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce (assertion); main broker-wipe test (assertion) |
| M16 | a crash after the confirm loses all future recovery | KILLED | aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce (assertion); aFreshInstanceResumesRecoveryFromPostgresAfterTheOldOneDied (assertion) |
| M17 | a recovery failure hot-loops | KILLED | aBrokerOutageDuringRecoveryBacksOffAndNeverTerminatesTheRun (assertion) |
| M18 | the queue deadline no longer wins the race | KILLED (after test fix) | SURVIVED the first strict run: the pre-publish lease check masked the deadline re-check in its test. The test now holds a lease longer than its wait; rerun: aDeadlineThatPassesBetweenTheRecoveryClaimAndItsPublicationWins (assertion) |
| M19 | cancellation no longer wins the race | KILLED | cancellationThatCommitsBeforeTheRepublishWinsAndNothingIsPublished (assertion) |
| M20 | transport recovery creates attempt #2 | KILLED | main broker-wipe test (assertion) |
| M21 | transport recovery carries an assignment epoch | KILLED | main broker-wipe test (assertion) |
| M22 | consumer duplicate detection removed | KILLED | aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce (assertion); aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution (assertion) |
| M23 | a semantic digest conflict is accepted | KILLED | aKnownIdentityCarryingDifferentBytesIsAnIntegrityConflictAndNotADuplicate (assertion) |
| M24 | a missing inbox marker suppresses recovery | KILLED | main broker-wipe test (assertion) |
| M25 | the inbox-retention assumption is broken | KILLED | recoveryHistoryAndTheDeliveredMarkerAreRetainedAndOnlyGrow (assertion) |
| M26 | production does not run recovery | KILLED | theProductionManagementPortServesHealthDeploymentAndMetricsAndNothingElse (assertion); theProductionProfileTurnsMigrationOffAndCannotBeTalkedOutOfIt (assertion) |
| M27 | an unpublished outbox row enters the recovery path | KILLED | aDispatchTheRelayNeverPublishedIsTheRelaysNotRecoverys (assertion) |
| M28 | recovery consults RabbitMQ state | KILLED | dispatchRecoveryDecidesFromPostgresAloneAndNeverFromBrokerState (assertion) |
| M29 | metrics use a run identity as a label | KILLED | recoveryMetricsCarryNoIdentity (assertion) |
| M30 | the broker-wipe test passes without an initial message existing | KILLED | main broker-wipe test (assertion) |
| M31 | the broker-wipe test passes without the message being destroyed | KILLED | main broker-wipe test (assertion) |
| M32 | the run succeeds through the original copy, not the recovery | KILLED | main broker-wipe test (assertion) |
| M33 | the stable-identity assertion is removed while identity breaks | KILLED | main broker-wipe test (assertion) |
| M34 | the duplicate race causes two runner claims | KILLED | aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution (assertion) |
| M35 | a terminal run is resurrected by a delayed recovered message | KILLED | aRepublishedCopyThatArrivesAfterTheDeadlineIsRefusedAndTheRunIsNotResurrected (assertion) |

## 41. Harness validity

Each mutant's anchor must match exactly once and the applied diff is recorded; the killing suite's old results are
deleted first; the suite must have run and executed tests; a mutant is KILLED only by an assertion failure or a
behavioural timeout in one of the tests named for it, or by the specific guard refusal that is its intended
protection — any other failure is INVALID; files are restored byte for byte and the worktree is compared before
and after. An earlier version of this harness counted any error as a kill; the review caught it (M06 was being
"killed" by a SQL bind error its own mutation caused), and it was rewritten before the recorded run. Where a
predicate is removed, bind placeholders are kept so mutated SQL still runs. Mutants masked by defence in depth
(M06–M10, M35) remove every layer that enforces the property, so each represents a real defect.

## 42. Local verification

macOS, Docker Desktop, no runsc:

| Suite | Result |
|---|---|
| `./gradlew clean check` (the backend job) | BUILD SUCCESSFUL |
| `:apps:api:test` + `:apps:api:deploymentReadinessTest` | 419 tests, all pass (after one fix: a recovery lease cross-check applied while recovery was disabled broke two unrelated suites; now enforced only when enabled) |
| `DispatchRecoveryTests` / `BrokerLossMeasurementTests` | 16 / 4 |
| `RunnerDaemonLifecycleTests` | 18, three consecutive runs |
| mutation battery | 35/35 killed (§40) |

CI-only, not faked: the runsc end-to-end recovery (`DeploymentPipelineTests` @Order 35) and the leak check.

## 43. CI evidence

| Run | Head | Result | Note |
|---|---|---|---|
| [36490418654](https://github.com/yannisyoussef/kaas/actions/runs/36490418654) | `2ff5694` | 10/11 | every recovery and race gate passed; a pre-existing instantaneous readiness assertion in the runner refresh test sampled a transient transport error. Test fixed to wait and report conditions |
| [36491575187](https://github.com/yannisyoussef/kaas/actions/runs/36491575187) | `b04a9a4` | **11/11** | final |

deployment-readiness (36491575187): control-plane suites WorkerClaimEndpointTests 12, BrokerLossMeasurementTests 4,
DispatchRecoveryTests 16, AttestationSubmissionTests 8, MigrationModeTests 8, DeploymentStatusTests 5; runner
suites 18/3/4/5/4/2; DeploymentPipelineTests 6 — all executed, none skipped. Evidence gates: dispatch recovery,
duplicate race, crash recovery, cancellation race, deadline race, late copy, deadline fail-closed, the production
composition, and the runsc recovery path — all as in §33–38. Shipped migrator: `applied=15 version=15`, then
`applied=0`, application role `sqlState=42501`. Release rehearsal `release_manifest=VALID`; refused relabelled,
tagged, missing. Leak check `containers=0 networks=0 runsc_processes=0`. Printed:
`verdict=MESSAGING RECOVERY COMPLETE — APPLICATION DEPLOYMENT CONTRACT READY`.

No existing gate was weakened; the other ten jobs' steps and counts are unchanged. Branch protection is not
asserted.

## 44. Deployment contract update

[application-contract.md § RabbitMQ loss](docs/deployment/application-contract.md#rabbitmq-loss): RabbitMQ is not
authoritative; exact guarantee, grace, cadence, spacing, cap, lease, validation, stall threshold and the
queue-deadline bound; what is not claimed; `KAAS_CONSUMER_NAME` must be fixed.

## 45. Ops impact

Application correctness no longer relies on RabbitMQ backup/restore for lost queued execution dispatches. Backup
policy is Operations' decision; the application does not say never to back RabbitMQ up.

## 46. Remaining residuals

- At least once: duplicates of a dispatch reach the consumer (decided once).
- A consumer backlog longer than the grace republishes waiting dispatches, at most `max-publications` copies each.
- A crash after confirm and before record is not counted in `recovery_publications`; the cap can be exceeded by
  the copies such crashes produce, bounded by the deadline.
- Pre-existing: a malformed body published first under a genuine message id leaves that run to its deadline.
- `kaas.consumer.name` must never change.
- The mutation battery runs locally, not in CI; the recovery evidence lines are written after the assertions they
  summarise, so the gate re-states JUnit success rather than adding independent evidence.

## 47. Final verdict

**MESSAGING RECOVERY COMPLETE — APPLICATION DEPLOYMENT CONTRACT READY**

Real broker loss is reconstructed from PostgreSQL, and the recovered run completes through the real production
path — RabbitMQ, API consumer, runner HTTP claim, ExecutionLoop, gVisor, Karate — as `COMPLETED/PASSED`, in CI run
36491575187 on `b04a9a4bb62a9ff1f34157c4fdc0bc8c85aa66f0`.

Independent reviews (outbox and publication semantics and crash windows; inbox idempotency, races and
multi-instance behaviour; PostgreSQL concurrency and migration; observability, contract, mutation anti-vacuity
and CI evidence) found no P0. Their P1s — a lease computed later than the database's, a default lease equal to
the queue timeout, eligibility scanning all outbox history, an unfaithful mutant and a harness that counted
errors as kills, a cross-clock assertion in the runsc test — were fixed before the final run, as were the guard
gaps and documentation findings. Answers to the prompt's questions: recovery creates no new logical dispatch; a
delayed original and its recovery execute once; a delivered-but-unclaimed run is never republished; the durable
fact is the `dispatch_inbox` row; a death after the confirm causes a duplicate, never a loss; neither the deadline
nor cancellation can be outrun into a resurrection; `published_at` is never reset; two instances publish one copy
per spacing interval; after a total wipe, exactly the rows listed in dispatch-recovery.md regenerate work.

## 48. Recommended next action

Stop application feature development; do not start KAAS-23. Return to Operations: apply the KAAS-22 Vault policy
permission; provision the staging execution host; install and register the pinned runsc; establish WireGuard;
configure TLS for the internal API over WireGuard; provision KaaS PostgreSQL and RabbitMQ; deploy the five
immutable images through the GitLab process; configure monitoring and backups; perform the real staging
rehearsal. Application development resumes toward KAAS-23 only after that rehearsal succeeds.
