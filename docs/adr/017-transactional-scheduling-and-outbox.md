# ADR-017: Schedule runs through one transaction that couples the attempt, dispatch intent, and outbox

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** The single `CREATED → QUEUED` lifecycle transition, `ExecutionAttempt` #1, the immutable queue-time `DispatchIntent`, and durable outbox persistence. No broker, worker, claim, or execution.

## Context

ADR-016 left a run durable but inert: `CREATED`, `runVersion` 1, no queue timing, no attempt, no message. Something must start the run without granting execution authority.

Two separable problems meet here.

The first is atomicity. A run that says `QUEUED` while no durable message exists is a silently lost run; a message that exists while the run says `CREATED` is a phantom execution. Both are unacceptable, and both are the normal outcome of writing state and publishing separately.

The second is authority. ADR-013 describes an at-least-once protocol whose `ExecutionCommand` carries an assignment epoch, a lease, a source-bundle capability, and a runtime secret capability. None of those values exist at scheduling time, because no worker has claimed anything. Writing them at queue time would mean inventing them — minting security capabilities before there is a bearer, and persisting them, unexpired, in a durable message that anything downstream could read.

## Decision

### Queue-time dispatch intent is not a claim-time execution command

The two are distinct message types with distinct producers, distinct contents, and distinct security properties.

```
Scheduler ──▶ DispatchIntent ──▶ (future broker) ──▶ (future worker claims attempt)
                                                            │
                                        assignment epoch ───┤
                                        active lease ───────┤
                                        source capability ──┤
                                        secret capability ──┘
                                                            │
                                                            ▼
                                                    ExecutionCommand ──▶ (future runner)
```

`DispatchIntent` says *this attempt of this run is waiting to be claimed*. It is transport neutral and carries only identity: schema/message/dispatch identity, tenant and project, run and semantic `runVersion`, attempt identity and number, the sealed `RunSnapshot` identity and digest, the server-owned queue deadline, and its own semantic digest. Sixteen fields, closed to all others.

It deliberately carries no assignment epoch, worker identity, lease, secret capability, source capability, presigned URL, object-store location, Docker configuration, host path, credential, broker routing key, or feature source. `runner-command.schema.json` is retitled to state that it is claim-time only and is never produced by scheduling. This slice produces no `ExecutionCommand` at all.

### One transaction, one bundle

A single PostgreSQL transaction locates the authorized run, verifies `CREATED` and the expected `runVersion`, then writes the transition, `ExecutionAttempt` #1, the `DispatchIntent`, the lifecycle event, and the outbox record together. `QUEUED` therefore means a durable attempt, dispatch, and unpublished outbox message all exist. Any failure leaves `CREATED` with none of them.

The invariant is enforced by the database rather than trusted to the application. Deferred constraint triggers on all five tables re-check, at commit, that a `QUEUED` run has exactly one complete attempt/dispatch/event/outbox bundle whose digests, versions, and timestamps agree — and that scheduling children never exist for a run that is not `QUEUED`.

### Compare-and-set, not locking

The transition is a conditional `UPDATE` predicated on `organization_id`, `run_id`, `lifecycle_state = 'CREATED'`, and the expected `run_version`, preceded by `SELECT … FOR UPDATE` on the same predicate. Concurrency is resolved by PostgreSQL row locking and re-qualification, never by `synchronized`, a JVM lock, or an application singleton — a control plane that scales horizontally cannot rely on any of those.

Concurrent schedulers therefore produce exactly one semantic winner. Losers observe the run as already `QUEUED` and perform no durable work. Idempotency is a property of state and invariants, not of a client-supplied key: scheduling is not a public operation and has no `Idempotency-Key`.

### Scheduling stays internal

There is no `POST /api/v1/runs/{runId}/schedule`. Scheduling is an application use case invoked in-process; the only public evidence is that `GET /api/v1/runs/{runId}` later reports `QUEUED`, `runVersion` 2, server-owned queue timing, and ETag `"run-2"`. The execution attempt is deliberately withheld from the public representation: it is infrastructure history, not run state, and exposing it would leak scheduling internals into a contract we are not ready to keep.

### Fail-closed mutation is narrowed, not removed

ADR-016's V3 trigger rejected every `test_runs` update. V4 replaces it with a guard that permits exactly one shape: `CREATED → QUEUED`, with `run_version` incremented by exactly one, queue timing set coherently, an attempt attached, `updated_by` equal to the scheduler actor, and — enforced by `to_jsonb` difference — every other column byte-identical. Every other lifecycle mutation, and every `DELETE`, still fails closed. Attempts, dispatches, lifecycle events, and outbox messages reject `UPDATE` and `DELETE` outright.

### The digest is semantic, not serialization bytes

The dispatch digest reuses ADR-016's canonicalization: a versioned format tag (`kaas.execution-dispatch.v1`) over four-byte big-endian length-prefixed UTF-8 fields in a fixed total order. It never depends on JSON key ordering or whitespace.

Timestamps are normalized before digesting — parsed, then rendered as UTC with exactly six fractional digits. Digesting the wire form instead would make the digest reproducible only in Java: `Instant.toString()` emits zero, three, six, or nine fractional digits depending on the value, so a consumer that normalizes to microseconds would compute a different digest for roughly one message in a million and report it as an integrity conflict — that is, as a suspected tenant-substitution attack, on valid traffic. The full rules and a frozen test vector are published in `packages/api-contracts/README.md` so other languages can verify independently.

Same message identity with the same digest is an exact duplicate; same identity with a different digest is an integrity conflict, and the database rejects it.

### The payload guard fails closed

A trigger requires the stored JSON payload to match its trusted semantic columns field by field, so the payload cannot drift from the columns the system reasons about. Two properties matter and neither is free.

It asserts the exact **key set**, not the key count. Counting keys lets a payload drop a required field and add an attacker-chosen one at the same cardinality.

It is written as `IF <every check passes> THEN RETURN NEW; … RAISE`, never as `IF <something is wrong> THEN RAISE`. In SQL, `payload->>'absent'` is NULL, and `false OR NULL` is NULL, so a chain of "is this field wrong?" tests **accepts** a payload whose field is missing or JSON null. The whole conjunction is additionally wrapped in `coalesce(…, false)`. JSON scalar types are checked too, so a string is not silently coerced where the contract requires a number.

### Persist the outbox; publish nothing

The outbox row is written and left unpublished — `published_at` null, `publish_attempts` zero. Nothing reads it. Immutable message identity and content are separated from the mutable delivery metadata a future relay will own, and the delivery columns are currently pinned to their initial values by a check constraint, so no code can pretend to have published. The API runtime dependency graph still contains no AMQP client. No consumer exists, so no inbox is implemented; ADR-013 continues to hold the deduplication requirement for whoever builds it.

The bundle-completeness invariant deliberately does **not** read the delivery columns. Coupling a run-state invariant to relay-owned state would make an unrelated `test_runs` update fail at commit as soon as a message is legitimately published. Existence, identity binding, digest agreement, and timestamp agreement are the invariant; delivery is not.

### Scheduling is implemented but not triggered

Nothing invokes the scheduler in production: no timer, no relay, no endpoint. A deployed run stays `CREATED`. This is deliberate — a trigger without a publisher would queue runs that nothing can dequeue — but it means the OpenAPI description must say so rather than advertise `QUEUED` as observable behaviour. The trigger ships with the relay.

## Alternatives considered

### Publish to RabbitMQ inside the transaction

Rejected. A broker publish cannot participate in a database transaction, so the write and the publish can disagree in both directions. The outbox exists precisely to make the durable decision atomic and to defer delivery to a component that can retry.

### Reuse `runner-command.schema.json` for the queue message

Rejected. It would require inventing an assignment epoch and runtime capabilities before a worker exists, which is both untrue and a security regression.

### Application-level locking or `SELECT` then `UPDATE` without a predicate

Rejected. Neither survives more than one control-plane instance, and a blind update would silently clobber a concurrent transition instead of losing cleanly.

### Expose scheduling as a public endpoint

Rejected for this slice. Scheduling triggers are a product decision the repository has not made; shipping a URL now would commit us to supporting it. An internal use case can be driven later by a relay, a timer, or an endpoint without breaking a published contract.

### Model attempts as a mutable row updated on claim

Rejected. Attempts are infrastructure history. Keeping them insert-only means claim, assignment, and lease become explicit new state rather than a silent overwrite of the queue-time record.

## Consequences

- `QUEUED` is now a trustworthy claim: the durable execution intent provably exists.
- Queue timing is server owned and starts only at `QUEUED`, preserving ADR-016's correction. Clients cannot influence the deadline; the timeout is configuration (`kaas.scheduling.queue-timeout`).
- The database state machine grew significantly. It is deliberate and readable, but each future transition must extend the guard rather than relax it, and the guard must be revisited as a unit when claim lands.
- `uq_execution_attempts_one_per_run` currently pins the MVP to a single attempt. Infrastructure retry (attempt #2) requires dropping it together with the `attempt_number = 1` checks — a schema change, which is the intended friction.
- The outbox has no relay, so messages accumulate unpublished. That is correct for this slice and must be paired with the publisher in the next one.
- A future claim slice must add assignment epoch, lease, and short-lived capabilities as new claim-time state, and only then may produce an `ExecutionCommand`.

## Independent review

Five specialist reviews (backend/domain, PostgreSQL integrity, distributed systems, API/security, quality engineering) read the diff rather than a summary. They found one critical and several substantive defects, all fixed here and covered by new tests:

- The payload guard **failed open** on any absent or JSON-null field, and on a dropped-key/added-key substitution that preserved the field count. Four working exploits were demonstrated against a live database. Fixed by the fail-closed rewrite above.
- Run creation stamps audit fields from the **application** clock while scheduling read the **database** clock, so ordinary NTP drift could make `NEW.updated_at >= OLD.updated_at` fail and reject a valid transition until wall time caught up. Queue start is now clamped so it can never precede the run's own last update.
- The bundle invariant read the outbox's mutable delivery columns (above).
- The update guard required `cancellation_status` to be *unchanged* rather than `NOT_REQUESTED`, so a cancelled run could be queued by a direct statement.
- Payload timestamp validation depended on the session `TimeZone` for offset-less strings; an explicit offset is now required.
- Row triggers do not fire for `TRUNCATE`, which would have erased the evidence tables silently. Statement-level guards were added.
- The dispatch payload used the shared web `ObjectMapper`, so an unrelated `spring.jackson.*` change could have broken scheduling at runtime; it now uses a private mapper.
- `registerSynchronization` throws when no synchronization is active, letting a **logging** concern fail the use case. It is now guarded.
- The outbox `occurred_at` was unconstrained despite being the future relay's ordering key; it is now bound to the dispatch.

## Verification

PostgreSQL 16.10 Testcontainers cover the success path (state, version, ETag `"run-2"`, queue timing, attempt #1 with no assignment, dispatch, unpublished outbox), rollback leaving no durable intent, a partial bundle rejected at commit by the deferred triggers, ten concurrent schedulers yielding exactly one winner with proven overlap, six distinct runs scheduled concurrently, repeat scheduling as a no-op, stale-version and cross-tenant rejection with 404 concealment, the payload exploits above, canonical payload with no claim-time authority, digest recomputation, the V1→V4 migration chain, and database rejection of every unsupported mutation. Every database rejection asserts its own guard's message, because all guards share SQLSTATE 23514 and asserting the exception type alone cannot tell which one fired.

Contract fixtures validate the dispatch schema and its forbidden-field boundary, and the published canonical digest vector is independently reproduced. Architecture and dependency guards confirm the control plane still cannot schedule via a timer, launch a process, or reach a broker.
