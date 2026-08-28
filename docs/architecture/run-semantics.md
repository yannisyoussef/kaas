# Run Semantics

**Status: PARTIALLY IMPLEMENTED.** TestRun intent, exact initial orthogonal dimensions, immutable RunSnapshot persistence, authenticated create/read/list/snapshot APIs, the pure lifecycle transition oracle, the atomic `CREATED → QUEUED` scheduling transition with its execution attempt, queue-time dispatch intent, and transactional outbox, broker publication through the relay, durable consumption with a consumer inbox, the authoritative `QUEUED → CLAIMED` claim with assignment-epoch fencing and a server-controlled lease, and every implemented way a run can end — cancellation from `CREATED`, `QUEUED`, or `CLAIMED`, the queue deadline from `QUEUED`, and lease loss from `CLAIMED` — are implemented. Provisioning, execution commands, source and secret capabilities, SSE, quality evaluation, results, artifacts, and execution remain proposed and absent.

## Requirements and constraints

KaaS needs a durable asynchronous run model that remains correct under duplicate delivery, concurrent cancellation, stale workers, and partial infrastructure failure. The MVP favors explicit evidence and deterministic recovery over automatic retries or a large orchestration framework. Arbitrary execution remains disabled.

Conceptual actors describe responsibility, not deployable services:

- **API actor:** validates an authenticated request, authorizes project scope, and records intent.
- **Scheduler actor:** moves accepted intent to an execution attempt and publishes through an outbox.
- **Worker actor:** claims an attempt, renews its lease, and reports bounded evidence.
- **Result processor:** validates and normalizes result/artifact evidence.
- **Reconciler:** applies deadlines, fences expired assignments, and terminalizes abandoned work.
- **Quality evaluator:** evaluates a control-plane policy over immutable canonical results.

## Orthogonal run dimensions

`TestRun` never uses one status to mean several things.

| Dimension | Values | Meaning |
|---|---|---|
| `lifecycleState` | `CREATED`, `QUEUED`, `CLAIMED`, `PROVISIONING`, `RUNNING`, `COLLECTING_RESULTS`, `PROCESSING_RESULTS`, `STOPPING`, `COMPLETED` | Where orchestration is now. Only `COMPLETED` is terminal. |
| `cancellationStatus` | `NOT_REQUESTED`, `REQUESTED`, `ACKNOWLEDGED` | Whether user cancellation was requested and made effective. It is not test evidence. |
| `testOutcome` | `PASSED`, `FAILED`, `NOT_AVAILABLE` | Trustworthy final test evidence. It is absent until completion. |
| `infrastructureOutcome` | `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` | Whether KaaS completed the execution protocol reliably. |
| `qualityGate.status` | `PASSED`, `FAILED`, `NOT_EVALUATED` | Control-plane policy evaluation over accepted immutable results. |

The API may encode pre-terminal `testOutcome` and `infrastructureOutcome` as `null`; durable domain data treats them as unset, not as a fifth outcome.

Examples:

- `COMPLETED + PASSED + SUCCEEDED + FAILED` means execution and assertions succeeded, but the separately versioned quality policy failed.
- `COMPLETED + NOT_AVAILABLE + FAILED + NOT_EVALUATED` means the runner failed before trustworthy test evidence existed.
- `COMPLETED + NOT_AVAILABLE + TIMED_OUT + NOT_EVALUATED` means a phase deadline won the race.
- `COMPLETED + NOT_AVAILABLE + CANCELLED + NOT_EVALUATED` means user cancellation became effective.

## Outcome invariants

1. Canonical outcomes are set only when lifecycle reaches `COMPLETED` and are immutable afterward.
2. `SUCCEEDED` requires `testOutcome` `PASSED` or `FAILED` and a `COMPLETE` structured result.
3. Any non-success infrastructure outcome yields canonical `testOutcome=NOT_AVAILABLE` for the MVP. Partial runner evidence may be retained for diagnostics but is not quality evidence.
4. A zero-test discovery after acceptance is not success. It is a structured infrastructure/validation failure with `NOT_AVAILABLE`; an empty selection known at request time is rejected before run creation.
5. `CANCELLED` requires `cancellationStatus=ACKNOWLEDGED`; a late cancellation that loses to accepted results does not rewrite outcomes. The converse is enforced for the queue deadline specifically: a `QUEUE_DEADLINE` termination requires `cancellationStatus=NOT_REQUESTED`, so a queue timeout is never reported as though somebody asked for it. It is deliberately *not* asserted of `TIMED_OUT` in general, because a run cancelled while running and then timed out really does carry an outstanding request.
8. An owned run cannot become terminal in one step. `CLAIMED` passes through `STOPPING`, where its assignment is fenced, before it can complete; a terminal run may retain no live assignment.
9. A terminated run records why and where: `terminationReason` and `terminationPhase` are set exactly when the run is `COMPLETED`, each reason pins one infrastructure outcome, and the phase vocabulary is the runner result contract's structured-error phases rather than a second private enum.
6. `qualityGate` is owned by the control plane, has an independent `evaluationVersion`, and never appears in runner output.
7. A quality-gate change never changes lifecycle, test outcome, infrastructure outcome, or raw result evidence.

## Run and execution attempt

A run is the user-visible orchestration identity. Its separately persisted immutable `RunSnapshot` captures exact input meaning. An `ExecutionAttempt` is one infrastructure assignment for that run. Attempt #1 is implemented and is created by scheduling in state `WAITING_FOR_CLAIM` with `attemptId` and one-based `attemptNumber` only: `assignmentEpoch`, lease data, and command identity are claim-time authority that does not exist until a worker claims the attempt, and are deliberately absent.

The schemas model attempts now, but the MVP permits exactly one. Automatic infrastructure retry is disabled. A future retry must create a new `attemptId`, increment `attemptNumber` and `assignmentEpoch`, and publish a new immutable command. A user-requested rerun creates a new run until same-run retry policy is designed.

Karate scenario retry is different: it occurs inside one execution attempt and appears as ordered, one-based `scenario.attempts[]`. It never changes the infrastructure attempt or run version by itself.

## Optimistic concurrency and fencing

Every durable run mutation compares `expectedRunVersion`; success increments `runVersion` exactly once and atomically writes its audit/outbox effects. No design relies on a distributed lock.

`assignmentEpoch` is a separate monotonic fencing token for worker ownership. Heartbeats renew the current lease only when run, attempt, and epoch all match. Heartbeats update lease bookkeeping without incrementing `runVersion` or emitting public lifecycle events. A fenced worker's late heartbeat, result, or manifest is stale even if its attempt ID is otherwise valid.

Consumer-supplied organization/project/run/attempt IDs never select authoritative tenant data. Receivers load the trusted assignment and compare the full identity tuple, command ID, version, epoch, deadline, and digest before effects.

## Terminality and evidence

`COMPLETED` is the single terminal lifecycle state; the orthogonal outcomes explain why and what evidence exists. This avoids duplicating `CANCELLED`, `TIMED_OUT`, or `FAILED` as both state and outcome. Terminal lifecycle, canonical outcomes, accepted result, and manifest bindings are immutable. Later derived quality evaluations are append-only/versioned interpretations.

The complete state machine, timeouts, and race rules are in [run-state-machine.md](run-state-machine.md). Message and persistence semantics are in [messaging-reliability.md](messaging-reliability.md).
