# Early Terminal Lifecycle Slice Report

## 1. Executive summary

The run lifecycle had no exit. That was not a missing feature; it was a live defect in the slice that shipped before it.

The admission ceiling counts every run that is not `COMPLETED`, and nothing could reach `COMPLETED`. So an organization that legitimately created its quota could never create another run — not later, not after everything drained, not ever, because nothing drained. The queue deadline had the same shape: the scheduler has written `queue_deadline_at` since the first scheduling slice and nothing read it, so an unclaimed run held one queued slot and one active slot indefinitely. And a tenant who started a run by mistake had no way to stop it.

This slice adds exactly three transitions — `CREATED → COMPLETED` (cancel), `QUEUED → COMPLETED` (cancel), `QUEUED → COMPLETED` (queue deadline) — and rewrites, as a single unit, the guards that had been written on the assumption that `CREATED → QUEUED` was the only mutation that could exist.

No worker claim, no assignment epoch, no execution, no result. RabbitMQ publication is unchanged except that a message can now be withdrawn before anyone claims it.

## 2. Why cancellation here needs no protocol

`CREATED` and `QUEUED` runs belong to nobody. No worker holds them, no container is running, nothing has to be asked to stop and nothing can refuse. Cancelling them is one transition, and the request and its acknowledgement are the same transaction — by the time the caller is told anything, the run is over.

That is why `cancellation_status = 'REQUESTED'` is never persisted in this slice. It exists in the schema for the phases a worker owns, where a request genuinely waits for cooperation. Writing it here would model a wait that does not happen, and the previous version of one integration test had to forge exactly that state with a trigger disabled in order to exist at all. It now cannot exist: the database rejects a cancellation status with no request time.

There is correspondingly no `STOPPING` phase, no cancel command in the outbox, and no grace timer. Those belong to the claim slice, where they will mean something.

## 3. A queue timeout is not a cancellation

Both are "the run ended without executing", and collapsing them would be easy and wrong. `TIMED_OUT` means the platform did not get to it in time. `CANCELLED` means a person decided. In an audited record those are different facts about different actors.

| Reason | Phase | Infrastructure outcome | Cancellation status | Actor |
|---|---|---|---|---|
| `USER_REQUESTED` | `CANCELLATION` | `CANCELLED` | `ACKNOWLEDGED` | the tenant's principal |
| `QUEUE_DEADLINE` | `QUEUE` | `TIMED_OUT` | `NOT_REQUESTED` | `kaas.queue-reaper` |

Enforced in both directions by the database: V3 already required `CANCELLED ⇒ ACKNOWLEDGED`, and V7 adds `TIMED_OUT ⇒ NOT_REQUESTED` and a constraint pinning each reason to exactly one outcome and one phase.

The same reasoning drives the API. Cancelling a run that already timed out returns `409 RUN_ALREADY_TERMINAL`, not a courteous `200`. A `200` would tell the caller their cancellation took effect, and the record would then say a person caused something no person caused.

`termination_phase` reuses the phase vocabulary the runner result contract already defines for structured errors, rather than inventing a private second enum that would need reconciling the first time a runner-reported failure and a control-plane termination appeared in one view. `termination_reason` is deliberately closed to the two reasons implemented here; a later slice extends it on purpose.

## 4. Idempotency is by state, not by key

The contract advertised an `Idempotency-Key` on this endpoint. It is not required and not used, and the contract was changed to say so.

The run is the idempotency scope. There is exactly one run to cancel; repeating the request returns the same terminal run, writes nothing, and produces no second version or event. A key would be scoped to this run's own path — so it could only catch a mistake the run's own state already catches. Storing an idempotency record for it would be a write that buys nothing and a second source of truth to keep consistent.

Tenant scoping is unchanged and total: a run belonging to another organization returns the same `404` as one that does not exist. Not `403` — telling another organization that a run exists is itself the leak.

## 5. Withdrawing a dispatch that was never sent

A cancelled run whose dispatch still publishes sends a worker to execute a run that is already over. The transaction that ends the run therefore marks its pending outbox row.

| Row state when the run ends | Action | Why |
|---|---|---|
| unpublished, no relay holding it | `SUPPRESSED_CANCELLED` / `SUPPRESSED_QUEUE_TIMEOUT` | nothing was sent, so nothing needs recalling |
| unpublished, in retry backoff | withdrawn, keeping its attempt history | withdrawn is not the same claim as never attempted |
| unpublished, claim lease expired | withdrawn, and the dead lease cleared | the relay reclaims exactly these rows, so leaving it means a later *first* delivery for a finished run |
| held under a live relay lease | none; it publishes and goes stale | it may already be at the broker, and a claim otherwise would be one the control plane cannot back |
| already published | none | at-least-once has always meant a consumer must reject stale work |

The predicate deliberately mirrors the relay's own reclaim predicate rather than being stricter than it. An earlier version skipped every claimed row, which sounds conservative and is not: the relay reclaims a row whose lease has expired, so a dispatch abandoned by a crashed relay was publishable but not withdrawable, and a later pass would have delivered it — for the first time — on behalf of a run that was already over. That is not the duplicate-delivery case at-least-once accepts.

Only a *live* lease is left alone. Racing that message would let the control plane pretend it can recall something it cannot; letting it publish and be rejected downstream is the honest version, and the consumer needs that rejection path regardless.

**Suppression is not a delivery failure**, and the schema says so structurally: a withdrawn row must be unpublished, while a delivery-failed row must carry an attempt and a reason. It must *not* be required to have zero attempts — an earlier draft required exactly that, which made cancelling any run whose dispatch had failed once impossible, and during a broker outage that is every pending run at once. A withdrawal that erased three real attempts to look tidier would be its own small lie. `TerminalDisposition` carries the distinction in code, `countTerminal()` derives its predicate from it, and a matching partial index means that count stays an index-only scan on a table that is never pruned.

Suppressed messages are also deliberately not requeueable. The requeue branch was narrowed to delivery dispositions, because replaying a suppressed message would dispatch work nobody is waiting for.

## 6. The guard rewrite

The two previous ADRs recorded a set that "must be rewritten together, as a unit": `require_complete_scheduling_bundle`, `guard_initial_execution_attempt`, `ck_run_lifecycle_events_schedule`, and the single-attempt constraints. This slice rewrites the parts of that set which early termination actually touches, plus two guards those ADRs did not name — `guard_supported_test_run_update` and `guard_run_lifecycle_event` — because they encode the same assumption. `guard_initial_execution_attempt` and the single-attempt constraints are deliberately left alone; they belong to claiming. Each of these encoded "only `CREATED → QUEUED` exists" in a different place, so patching one at a time would have left the combined invariant inconsistent.

| Guard | Was | Now |
|---|---|---|
| `guard_supported_test_run_update` | one exact schedule shape | schedule plus two terminal shapes; everything else still fails closed |
| `require_complete_scheduling_bundle` | `ELSIF` rejected any transition out of `QUEUED` | `QUEUED` still needs exactly one complete bundle; `COMPLETED` keeps its children and may gain none |
| `guard_run_lifecycle_event` | matched the `QUEUED` transition only | matches the terminal transition too, and now also checks the actor against `updated_by` |
| `ck_run_lifecycle_events_schedule` | `sequence = 1`, one shape, actor pinned | dropped, replaced by `ck_run_lifecycle_events_transition`: three shapes, with `run_version = sequence + 1` |
| `guard_outbox_message` | claim, release, publish, retry, terminal, requeue | plus suppression; requeue narrowed to delivery failures |
| `guard_run_scheduling_control` | `CREATED` only | `CREATED` or `QUEUED`, so the reaper can share the backoff table |

Four transitions are permitted, and nothing else. `QUEUED → CLAIMED` and everything past it still fail closed.

Two consequences worth naming explicitly:

- **`run_lifecycle_events.attempt_id` became nullable.** A run cancelled while still `CREATED` never had an attempt. The composite foreign key is `MATCH SIMPLE`, so a null skips the *whole* constraint — the tenancy and run binding along with the attempt. The reference genuinely does weaken; what replaces it is `guard_run_lifecycle_event`, which matches every event against the authoritative run row, and the transition-shape CHECK that ties the attempt's presence to the transition.
- **A terminal run keeps its scheduling children.** They are evidence of what really happened, stamped with the version they described. The bundle check therefore stops applying to them rather than demanding they match a version they predate — which is what would have happened if the `QUEUED` branch had simply been widened.

And terminalization ends a run's history without rewriting it: queue timing, the attempt reference, the snapshot digest, and the quality gate are outside the set of columns the terminal branch may move. Nothing ran, so the gate stays `NOT_EVALUATED`.

## 7. The reaper

It adds no lifecycle semantics of its own. It selects expired runs and calls the same use case a tenant's cancellation calls, so the same compare-and-set, the same guards, and the same suppression apply.

```
                     ┌──────────► terminated: COMPLETED / TIMED_OUT, control row deleted
                     │
QUEUED past deadline ┼──► lost the race (cancelled, or another replica) ──► control row cleared
                     │
                     ├──► transient failure ──► durable delay, bounded ──┐
                     │                                                    │
                     └──◄ next_attempt_at elapses              quarantined (operator clears)
```

Retry state is **shared with the scheduler's `run_scheduling_control`**, not duplicated. It is the same failure shape — a bounded technical retry that must survive a restart and be shared across replicas — and the two uses can never describe the same run at the same time, because scheduling deletes the control row the moment a run leaves `CREATED`. A second table would have needed its own guard, its own operator recovery story, and its own quarantine semantics for no gain.

Selection compares against the **database** clock, the same authority that stamped the deadline, and the terminal instant is clamped to be at or after it. An application-side comparison would let host drift select a run early, and the database's own `completed_at >= queue_deadline_at` guard would then reject the write — turning a clock skew into a permanently stuck run rather than a late one.

Reaping runs every 10s against scheduling's 2s. A deadline that has already passed is not more urgent for having passed a second ago, and this pass writes terminal state.

## 8. Capacity is genuinely released

The active ceiling counts `lifecycle_state <> 'COMPLETED'` and the queued ceiling counts `lifecycle_state = 'QUEUED'`, so a terminal run drops out of both. Verified from both directions: an organization at its active ceiling gets `429`, cancels one run, and the next creation succeeds; a run deferred at `CREATED` because the queue was full schedules on the next pass once a queued run is reaped.

This also removed a piece of test scaffolding that had been an honest admission of the defect. `completeQueuedRun` used to disable two triggers and forge a `COMPLETED` row, with a comment saying no implemented transition could do this and the schema actively forbade it. It now calls the real use case.

## 9. Migration and the permanent gate

V7 transforms no rows. But it adds ten validated CHECK constraints — six on `test_runs`, three replacing the outbox accounting rules, and one on `run_lifecycle_events` — drops a NOT NULL, and replaces five guard functions. Every one of those is checked against every row already in the table. Over an empty table that proves nothing, and would pass green while shipping a constraint production data violates. That is the same blind spot the permanent rule exists to close, in its other form.

So the populated-upgrade test now asserts, **before** the upgrade runs, that the fixture holds rows the pending migrations will act on: both lifecycle states, the `NOT_REQUESTED` cancellation status, lifecycle events carrying attempts, and every outbox delivery state. Afterwards it asserts that nothing was transformed — no invented completion time, reason, or cancellation timestamp, no blanked attempt reference, and no delivery state reinterpreted as a suppression.

Both directions pass. The known limits of the gate are unchanged and still recorded in the admission slice's documentation.

## 10. Observability

`kaas.run.terminated` (reason) and `kaas.queue-reaper.failures` (reason category). Both are counted **after commit**, not before: a rolled-back transaction that had already counted would report capacity being released that was never released. Neither is ever dimensioned by organization, project, run, or principal. Logs carry trusted resource identifiers and bounded reason codes.

## 11. What this slice deliberately does not do

- No cancellation of any phase a worker owns. `409` until claiming lands and a real `STOPPING` protocol exists.
- No `REQUESTED` cancellation status is ever written, because nothing waits.
- No new execution-attempt disposition. The attempt keeps `WAITING_FOR_CLAIM`; the run's terminal state already says what happened, and inventing an attempt outcome would describe an execution that never started.
- No expiry of `CREATED` runs. They are cheap, and nothing has established what a fair intent lifetime is.
- No consumer, inbox, claim, epoch, lease, ExecutionCommand, SSE, results, artifacts, quality-gate execution, or Karate execution.

## 12. Residual risks

- **A dispatch under a live relay lease when its run is cancelled still publishes.** By design, and now genuinely narrow: an expired lease is withdrawn instead, so this is only the window in which a relay actually holds the message. The consumer slice must reject stale dispatches by run version and lifecycle — the same duplicate-delivery obligation at-least-once already imposed, not a new one.
- **`CREATED` runs still accumulate** for a tenant that never cancels them and whose queue stays full. They are one row each, but they are not free, and nothing expires them.
- **Quarantined reaping needs an operator.** Recovery is deleting the control row; there is deliberately no endpoint while the failure modes are still being learned. The budget is deliberately generous — a quarantined expired run is holding admission capacity, so giving up on it is worse than retrying it for a long time — and `kaas.queue-reaper.quarantined` is the standing signal that one needs attention.
- **The migration gate baselines at the previous version only**, so a slice shipping two migrations would leave the earlier one tested against an empty database. Unchanged from the previous slice.

## 13. Verification

Java 25 / Gradle 9.7.1, PostgreSQL 16.10 and RabbitMQ 3.13 Testcontainers. 126 API tests plus 1 runner test, zero failures, zero skips. Contract, web, audit, Compose, and whitespace gates pass.

New coverage: immediate cancellation of a `CREATED` run with nothing dispatched and no attempt referenced by its event; admission capacity released so a creation that was refused at the ceiling succeeds and the ceiling then binds again; cancellation of a `QUEUED` run withdrawing its dispatch without spending an attempt, without becoming a dead letter, and with the relay leaving the withdrawn row untouched on its next pass; withdrawal of a dispatch already in retry backoff, keeping its real attempt history; withdrawal of a dispatch abandoned under an expired relay lease, clearing the dead lease; a live relay lease left alone while the run still terminates; a withdrawal refused when it names a live run or a reason that run did not end for, and refused outright for a message already published; idempotent repeat cancellation writing no second version or event; the closed cancellation vocabulary rejecting an invented reason, an empty body, and an unknown property; tenant-scoped `404` revealing no organization, project, or state; a reaped run never reported as cancelled, and a later cancellation of it returning `409` with the outcome unchanged; reaping releasing queue capacity so a deferred run schedules; eight concurrent cancellers and reapers producing exactly one terminal transition, one version bump, one event, a matching disposition, a decided answer for every loser, and no leftover eligibility state; reaping failures backing off durably, quarantining after the budget, and recovering by control-row deletion; every unimplemented terminal shape rejected by the guard; a termination unable to name a reserved platform actor or to attribute a queue expiry to a tenant, and a token unable to carry a reserved subject at all; a terminal transition refused when it writes no lifecycle event; each new CHECK constraint rejected independently with the guard disabled, so none is unreachable defence; lifecycle events unable to claim an actor or a version they did not cause; termination refused for a worker-owned phase by the aggregate itself and not only by the repository predicate that filters it first; and termination metrics carrying `reason` alone. Migration coverage runs fresh and populated-upgrade directions, with the populated fixture proven to reach what the upgrade changes before it runs.
