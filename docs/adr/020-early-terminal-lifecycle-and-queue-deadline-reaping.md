# ADR-020: End runs nobody has taken, and rewrite the scheduling-only guards as a set

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** Tenant-requested cancellation of CREATED and QUEUED runs, queue-deadline reaping, withdrawal of dispatches that were never published, and the rewrite of the guards that assumed `CREATED → QUEUED` was the only mutation that could exist. No worker claim, no execution, no result.

## Context

The admission ceiling introduced in ADR-019 counts every run that is not `COMPLETED`. Nothing could reach `COMPLETED`. So the ceiling was not a capacity ceiling at all — it was an availability ceiling: an organization that legitimately created its quota could never create another run, ever, and no operator action short of editing the database could help.

The queue deadline had the same shape of problem. The scheduler has written `queue_deadline_at` since the scheduling slice, and nothing ever read it. A run no worker claimed stayed `QUEUED` indefinitely, holding one queued slot and one active slot and keeping a dispatch alive for work that had already missed the window it was promised.

And there was no way for a tenant to stop a run they had just started. The contract has advertised a cancellation endpoint as `PROPOSED` since the first slice.

Three separate problems, one cause: the lifecycle had no exit.

## Decision

### Unowned work stops immediately, so there is no STOPPING phase

`CREATED` and `QUEUED` runs belong to nobody. No worker holds them, no container is running, nothing has to be asked to stop and nothing can refuse. Cancelling them is therefore a single transition, and the request and its acknowledgement are the same transaction: by the time the caller is told anything, the run is over.

This is why the `REQUESTED` cancellation status is not reachable in this slice. It exists in the schema for the phases a worker owns, where a request genuinely has to wait for cooperation. Persisting it here would model a wait that does not happen.

### A queue timeout is not a cancellation

They are both "the run ended without executing", and it would be easy to collapse them. It would also be a lie in an audited record: `TIMED_OUT` means the platform did not get to it in time, `CANCELLED` means a person decided. The database enforces the separation in both directions — a `TIMED_OUT` run may not carry a cancellation status, and each termination reason pins exactly one infrastructure outcome.

For the same reason, cancelling a run that already timed out is a `409`, not a courteous `200`. Reporting it as cancelled would write a cause nobody caused.

### The termination vocabulary is the one the result contract already has

`termination_phase` reuses the phase enum from the runner result contract's structured error. Inventing a second, private phase enum would have needed reconciling the first time a runner-reported failure and a control-plane termination appeared in the same view.

`termination_reason` is deliberately closed to the two reasons implemented here. A later slice extends it on purpose rather than inheriting an open vocabulary it never chose.

### Cancellation is idempotent by state, not by key

The run is the idempotency scope. There is exactly one run to cancel, so repeating the request returns the same terminal run and writes nothing. An `Idempotency-Key` would be scoped to this run's own path, so it could not catch a mistake that the run's state does not already catch — and the endpoint no longer requires one. The contract was changed to say so.

### A dispatch that was never published is withdrawn; one already claimed is not

A cancelled run whose dispatch still publishes would send a worker to execute a run that is already over. So the same transaction that ends the run marks any pending outbox row for it as suppressed.

The predicate is the relay's own reclaim predicate, not a stricter one: unclaimed, **or** holding a lease that has expired. Being stricter is the tempting mistake and the wrong one, because the relay reclaims an expired-lease row — so a dispatch abandoned by a crashed relay would be publishable but not withdrawable, and a later pass would deliver it for the *first* time on behalf of a finished run. A row in retry backoff is withdrawable for the same reason.

Only a live lease is left alone. That message may already have reached the broker, and suppressing it would be pretending the control plane can recall something it cannot. It publishes and goes stale instead — a duplicate-delivery case a consumer has to reject on its own terms regardless, because publication has always been at-least-once.

**Suppression is not a delivery failure.** It consumes no attempt, invents no failure code, and is excluded from the relay's dead-letter count. Counting it would make every cancellation look like a broker fault and drag relay health down with it.

But "not a delivery failure" is not the same claim as "never attempted", and conflating them is a real trap: a constraint requiring a withdrawn message to have zero attempts makes cancelling any run whose dispatch has failed once impossible — platform-wide during a broker outage, which is exactly when the queue backs up and the admission ceiling binds. What a withdrawal asserts is only that the message was never published. Whatever attempts it really made are kept.

### The four scheduling-only guards move as one

`guard_supported_test_run_update`, `require_complete_scheduling_bundle`, `guard_run_lifecycle_event`, and `ck_run_lifecycle_events_schedule` were each written when `CREATED → QUEUED` was the only mutation that could exist, and each encoded that assumption differently — one in a column-difference check, one in an `ELSIF` that rejected any transition out of `QUEUED`, one in a `WHERE lifecycle_state = 'QUEUED'`, one in `sequence = 1`. Patching them one at a time would have left the combined invariant inconsistent.

ADR-018 and ADR-019 named an overlapping but not identical set: `require_complete_scheduling_bundle`, `guard_initial_execution_attempt`, `ck_run_lifecycle_events_schedule`, and the single-attempt constraints. Two of those — `guard_initial_execution_attempt` and the single-attempt constraints — belong to claiming and are deliberately untouched here, and two guards those ADRs did not name are rewritten because they encode the same assumption. The unit is defined by the assumption, not by the earlier list.

They now permit exactly four shapes and nothing else: `CREATED → QUEUED`, `CREATED → COMPLETED` (cancel), `QUEUED → COMPLETED` (cancel), `QUEUED → COMPLETED` (queue deadline). `QUEUED → CLAIMED` and everything past it still fail closed.

Two consequences worth naming. `run_lifecycle_events.attempt_id` became nullable, because a run cancelled while still `CREATED` never had an attempt. The composite foreign key is `MATCH SIMPLE`, so a null skips the *whole* constraint — tenancy and run binding included, not only the attempt. That reference genuinely weakens; what carries the binding instead is `guard_run_lifecycle_event`, which matches every event against the authoritative run row, with the transition-shape CHECK tying the attempt's presence to the transition. And a terminal run keeps whatever scheduling children it had: they are evidence of what really happened, stamped with the version they described, so the bundle check stops applying to them rather than demanding they match a version they predate.

### Terminalization ends a run's history; it must not rewrite it

The terminal branch of the guard may move the lifecycle, version, outcomes, termination fields, cancellation fields, and audit stamps. Queue timing, the attempt reference, the snapshot digest, and the quality gate are deliberately outside that set. Nothing ran, so the gate stays `NOT_EVALUATED`; the scheduling record is what it was.

### Reserved actor names are refused at the token boundary

The subject of an authenticated token becomes `test_runs.updated_by` and `run_lifecycle_events.actor`. Those are audit evidence, and this slice makes some of them load-bearing: a scheduling event is only valid when its actor is `kaas.scheduler`, and a queue expiry only when it is `kaas.queue-reaper`.

So the `kaas.` prefix is refused where a subject enters the system, and the terminal guard independently pins the actor — the reaper's identity for a queue expiry, and anything *but* a reserved name for a tenant cancellation. Either check alone leaves a hole: without the first, a client that can choose its own subject records its actions as the platform's; without the second, any code path that ever writes a terminal row can attribute it to anyone.

### The reaper reuses the scheduler's backoff table rather than growing a second one

A run whose termination keeps failing must not be retried every tick forever. That is the same problem `run_scheduling_control` was built for in ADR-019, so the reaper writes to it, and the guard on that table was widened from `CREATED` to `CREATED` or `QUEUED`. The two uses can never describe the same run at the same time, because scheduling deletes the control row at the moment the run leaves `CREATED`.

Terminating a run deletes its control row for the same reason success does: nothing intends to act on a finished run, and a leftover row would keep a quarantine visible for work that no longer exists. Scheduling deletes it *inside* the transition, not after — clearing afterwards left a window in which a scheduler-written control row described a run that was already `QUEUED`, which the reaper now reads and would honour by withholding an expired run.

The budgets are separate, though, because the two passes park different costs. A quarantined `CREATED` run is one cheap row waiting for an operator. A quarantined `QUEUED` run is still holding an active and a queued admission slot — the exact condition this slice exists to clear — so reaping is given a far longer rope, and only a sustained partial outage can spend it. Sharing the scheduler's budget would have meant a ten-minute database wobble could park capacity indefinitely.

### A quarantined expired run is the alarm worth raising

`kaas.queue-reaper.quarantined` is separate from the scheduler's gauge and scoped by lifecycle state. Counting both passes in one number would attribute a parked slot to the scheduler, and the scheduler's pass never runs for a `QUEUED` run, so a shared gauge would never refresh for exactly the runs that matter most.

### The reaper reads the deadline with the clock that wrote it

Selection compares `queue_deadline_at` against the database clock, and the terminal instant is clamped to be at or after the deadline. An application-side comparison would let host drift reap a run before the deadline it is enforcing — and the database's own guard would then reject the write, turning a clock skew into a stuck run.

## Alternatives considered

### Deleting a cancelled run

Rejected. A run is evidence. The whole schema is insert-only with no-delete triggers, and a tenant being able to erase what they asked for would undo that.

### A `CANCELLING` or `STOPPING` state for unowned work

Rejected. It models a wait for cooperation that nobody is waiting for. When claiming lands and a worker really does own the run, `STOPPING` becomes the honest answer — for those phases.

### Suppressing a claimed outbox row anyway

Rejected. It is not possible to know whether the broker already has it, so the "suppression" would be a claim the control plane cannot back. Letting it publish and be rejected downstream is the honest version, and the consumer needs that rejection for duplicates in any case.

### Deleting the suppressed outbox row instead of marking it

Rejected for the same reason nothing else in the outbox is deleted: the row is the record of a decision. Its absence would be indistinguishable from a message that was never created.

### A separate retry table for the reaper

Rejected. It is the same failure shape as scheduling — a bounded technical retry that must survive a restart and be shared across replicas — and a second table would need its own guard, its own operator recovery story, and its own quarantine semantics for no gain.

### Terminalizing a run whose scheduling is quarantined

Rejected, again. A run that cannot be scheduled has not failed as a test and has earned no outcome. Quarantine is still the honest answer, and it is still technical state an operator clears.

## Consequences

- Admission capacity is now genuinely released. An organization at its ceiling can free a slot itself.
- A queue deadline is enforced rather than merely recorded, so an unclaimable backlog drains instead of accumulating forever.
- `test_runs` carries five new columns and six new check constraints, and the outbox and lifecycle-event tables carry four more between them. Every one is validated against existing rows at migration time, which is why the migration gate now proves the fixture is non-empty *before* the upgrade runs.
- The suppression dispositions are written by the control plane and the failure dispositions by the relay, into one column. The enum records which is which, and only the failures count as dead letters.
- Cancellation is available for exactly two lifecycle states. A run in any later phase gets a `409`; that stays true until claiming is implemented.
- Three background timers now run — scheduling, relay, reaping — so the pool default moved from two threads to three, and its environment variable was renamed to match what it now sizes.
- `ck_test_runs_timeout_not_cancelled` is scoped to the queue deadline rather than to `TIMED_OUT` in general. A run cancelled while `RUNNING` and then timed out really is `TIMED_OUT` with an outstanding cancellation request, and a constraint written over every `TIMED_OUT` row for all time would foreclose it.
- The reaper's selection takes a bounded candidate window before round-robining. Without it the planner will not use the queue-deadline index: that column's statistics are computed over the whole table, where accumulating terminal runs all have deadlines in the past, so the predicate is estimated at most of the queue and a scan wins on cost — and it degrades as terminal runs accumulate, which is the opposite of what a partial index on the live queue is for.

## Constraints the worker-claim slice must still rewrite

Unchanged and deliberately not weakened here: `guard_initial_execution_attempt` (it still requires `queued_at = created_at`, impossible for attempt #2), the single-attempt uniqueness and check constraints, and the `QUEUED` branch of `require_complete_scheduling_bundle`, which still demands exactly one bundle matching the run's current version. Claiming and infrastructure retry must rewrite those together.

## Verification

PostgreSQL 16.10 Testcontainers cover: immediate cancellation of a `CREATED` run with nothing dispatched and no attempt referenced by its event; admission capacity released so a creation that was refused at the ceiling succeeds and the ceiling then binds again; cancellation of a `QUEUED` run withdrawing its dispatch without spending an attempt, without becoming a dead letter, and with the relay leaving the withdrawn row untouched on its next pass; withdrawal of a dispatch already in retry backoff, keeping its real attempt history; withdrawal of a dispatch abandoned under an expired relay lease, clearing the dead lease; a live relay lease left alone while the run still terminates; a withdrawal refused when it names a live run or a reason that run did not end for, and refused outright for a message already published; idempotent repeat cancellation writing no second version or event; the closed cancellation vocabulary rejecting an invented reason, an empty body, and an unknown property; tenant-scoped `404` revealing no organization, project, or state; a reaped run never reported as cancelled, and a later cancellation of it returning `409` with the outcome unchanged; reaping releasing queue capacity so a deferred run schedules; eight concurrent cancellers and reapers producing exactly one terminal transition, one version bump, one event, a matching disposition, a decided answer for every loser, and no leftover eligibility state; reaping failures backing off durably, quarantining after the budget, and recovering by control-row deletion; every unimplemented terminal shape rejected by the guard; a termination unable to name a reserved platform actor or to attribute a queue expiry to a tenant, and a token unable to carry a reserved subject at all; a terminal transition refused when it writes no lifecycle event; each new CHECK constraint rejected independently with the guard disabled, so none is unreachable defence; lifecycle events unable to claim an actor or a version they did not cause; termination refused for a worker-owned phase by the aggregate itself and not only by the repository predicate that filters it first; and termination metrics carrying `reason` alone. Migration coverage runs fresh and populated-upgrade directions, with the populated fixture proven to reach what the upgrade changes before it runs.
