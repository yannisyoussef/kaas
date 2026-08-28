# ADR-019: Bound tenant amplification with admission control and make scheduler backoff durable

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** Per-organization run admission, a queued-run ceiling enforced at scheduling, durable scheduler retry and quarantine state, and a permanent migration-upgrade testing rule. No consumer, no worker claim, no execution.

## Context

Until the relay slice, run creation was inert: a `CREATED` row and nothing else. Now a single `POST /runs` automatically becomes a scheduled run, an execution attempt, a dispatch intent, a durable outbox row that is never deleted, and a message on a broker queue. Creation is authenticated but otherwise unbounded, so one tenant can convert request volume directly into storage and broker load — for every other tenant as well as itself. The relay slice's own report listed this as an unclosed residual risk.

Two smaller problems compound it. The scheduler's poison-pill cooldown lived in a process-local map, so a restart retried a failing run immediately and two replicas never agreed. And a migration had already shipped that applied cleanly to an empty database and would have failed on production data, because a fresh-schema migration test cannot exercise a backfill.

## Decision

### Admission is a ceiling on concurrent work, not a rate limit

An organization may hold a configured number of runs that are not yet complete. "Active" means every state that is not complete. Naming the two states that exist today would be the tempting choice and the wrong one: the ceiling would silently stop binding the moment `QUEUED → CLAIMED` lands, and because the partial index carries the same predicate, correcting it later would cost a migration rather than a line.

This is deliberately not a rate limiter, a billing plan, a token bucket, or a quota framework, and it does not introduce Redis. A ceiling on concurrent work is what bounds amplification; how fast a tenant *reaches* that ceiling is not the problem, because the ceiling is what determines the resident cost.

Limits are server configuration validated at startup. No request body, header, or token claim can influence them.

### Counting must happen under a lock, or it is theatre

Concurrency safety is the whole point. Without serialization, twenty simultaneous requests each observe the same pre-insert count, all pass, and the organization overshoots by nineteen. So admission takes a PostgreSQL advisory transaction lock scoped to the organization, counts, and creates — all in one transaction, consistent with how idempotent creation is already serialized.

The lock is taken *after* the existing per-key idempotency lock and only on the new-admission path, so the ordering is the same on every path and the two cannot deadlock. They also use distinct advisory lock classes rather than a shared key space, so no hash collision can invert that ordering. It is acquired as late as possible — immediately before the count and the write — because every request waiting on it holds a pooled connection, and a lock held across snapshot materialisation would let one tenant stall the whole pool. No counter table was introduced: there is no counter to keep consistent, and a counter would add a write-contention point and a reconciliation problem in exchange for a count that a partial index already answers cheaply.

### An idempotent replay is not new work

A replay that has already succeeded returns its original run even when the organization is now at its ceiling. It creates nothing, so refusing it would punish a client for retrying safely — exactly the behaviour idempotency exists to make free. Replay resolution therefore happens before admission.

A *new* key is new work and obeys the current ceiling. This is the subtle half, and it is tested from both directions.

### The queue ceiling belongs to the scheduler, not to creation

`CREATED` intent costs one row. Turning it into a queued run costs an attempt, a dispatch, a durable outbox record, and a broker message. Those are different prices, so they get different ceilings: active capacity gates `POST /runs`, and queued capacity gates `CREATED → QUEUED`.

When the queue is full the run simply stays `CREATED` with a durable next-attempt time. It is not a failure, accrues no failure count, and can never be quarantined for waiting. Deferring here rather than rejecting at creation is what keeps a burst from becoming broker traffic while still accepting the tenant's intent.

### Scheduler backoff is durable, and lives beside the run rather than on it

Retry state moved into `run_scheduling_control`. A restart now preserves the delay, and replicas share one view of it.

It is a separate table rather than columns on `test_runs` on purpose. Scheduling is technical infrastructure state; putting it on the aggregate would mean relaxing the guard that makes `CREATED → QUEUED` the only permitted mutation, trading a real lifecycle invariant for bookkeeping convenience. Nothing in this slice mutates a run's lifecycle or version.

Success deletes the control row. Absence means eligible, so there is exactly one representation of "nothing is holding this run back" and no stale eligibility can outlive the transition it was gating.

### A scheduling failure is not a verdict on a test run

Transient failures — a database timeout, an internal error — get an escalating bounded delay. A run whose trusted input cannot possibly be valid is quarantined immediately, because retrying it is guaranteed waste.

Either way the run stays `CREATED` with no outcome. Infrastructure being unhealthy says nothing about a test, so this slice invents no terminal lifecycle transition; quarantine is technical eligibility state that an operator clears by deleting the control row. That recovery path is documented rather than automated, because reviving a poisoned run is a decision.

### A fresh-schema migration test is not a migration test

Every migration is now verified twice: against an empty database, and against a database already carrying representative rows from the previous version, with that version's triggers still installed.

This is the rule the relay slice's defect earned. A backfill over zero rows cannot trip a guard, violate a constraint, or leave a NULL behind, so the empty-database run is silent about exactly the failures that matter.

The fixture has to hold rows the migration would actually select, which is subtler than it sounds: the first version of it seeded outbox rows with no dispatch reference, so a backfill joining on the dispatch would still have matched nothing. Verifying the gate therefore needs a probe in the real shape — joined and filtered — not an unconditional statement that would touch any row at all.

## Alternatives considered

### A counter table per organization

Rejected. It must be kept consistent with reality, becomes a write-contention hotspot, and needs reconciliation after any manual intervention — in exchange for replacing a count that a partial index already serves with an index-only scan.

### Optimistic check-then-insert with a unique constraint

Rejected. There is no natural uniqueness to violate at the Nth run, so nothing would fail closed; the check would simply be advisory and would overshoot under concurrency.

### Rate limiting instead of a concurrency ceiling

Rejected for now. Requests per second is the wrong dimension: the cost that hurts is resident work, and a slow steady stream would pass a rate limiter while filling the queue indefinitely.

### A single ceiling covering both creation and queueing

Rejected. It would force a tenant's burst to be refused at the API even though holding the intent is nearly free, and it would conflate two costs that differ by orders of magnitude.

### Storing backoff on `test_runs`

Rejected. It would require permitting a second class of mutation on the aggregate, weakening the guard that the claim slice still depends on.

### Terminalizing a run whose scheduling keeps failing

Rejected. There is no implemented terminal transition, and inventing one would give a run an outcome it has not earned. Quarantine is the honest equivalent and stays entirely in technical state.

## Consequences

- One tenant can no longer convert request volume into unbounded storage and broker load.
- Admission serializes run creation per organization for the duration of the transaction. That is a deliberate throughput ceiling per tenant, not per platform, and it is the price of a decisive count.
- A tenant that is at its queue ceiling accumulates `CREATED` runs. They are cheap, but they are not free, and nothing yet expires them.
- Quarantined runs need an operator. There is no self-service recovery and no endpoint; that is intentional while the failure modes are still being learned.
- The migration gate adds one PostgreSQL container to the suite and a fixture that must be extended whenever a migration touches a table the fixture does not seed.

## Constraints the worker-claim slice must still rewrite

Unchanged and deliberately not weakened here: `require_complete_scheduling_bundle`, `guard_initial_execution_attempt`, `ck_run_lifecycle_events_schedule`, and the single-attempt uniqueness and check constraints. They must be rewritten together, as a unit.

## Verification

PostgreSQL 16.10 Testcontainers cover admission at, below, and above capacity; replay succeeding at capacity while a new key is refused; tenant independence; twenty concurrent creates admitting exactly one; the request being unable to supply its own capacity; the queue ceiling holding runs at `CREATED` with no attempt, dispatch, or outbox; deferred runs scheduling once capacity frees; concurrent replicas not overshooting; durable delay surviving a freshly constructed scheduler; escalating delay; quarantine after the budget and after an impossible input; operator recovery; and metric labels carrying no tenant identity. Migration coverage runs both the fresh and the populated-upgrade directions.
