# Dispatch recovery

KAAS-MSG-001 · [ADR-036](../adr/036-postgres-authoritative-dispatch-reconstruction.md) · closes the blocker
measured in [ADR-035 §8](../adr/035-deployment-readiness-runner-claim-intake.md)

## Where the truth lives

```
PostgreSQL  (authoritative)
│
├── execution_dispatches        immutable ExecutionDispatch — identity, payload, digest
├── outbox_messages             initial publication: published_at = the FIRST confirmed publication
├── dispatch_recoveries         recovery state: republications, first/last, last failure, next, lease
└── dispatch_inbox              the consumer's durable decision — "the consumer saw this message"
        │
        ▼
RabbitMQ  (transport; may lose a message)
        │
        ▼
API consumer  ─▶ dispatch_inbox DELIVERED  ─▶ runner claim API  ─▶ runner ─▶ ExecutionLoop ─▶ runsc
```

## The recovery loop

```
every kaas.dispatch.recovery.interval, in every API instance:

  claim ≤ batch-size eligible dispatches                        (one short transaction; FOR UPDATE SKIP LOCKED)
     eligible = published EXECUTION_DISPATCH, not terminally disposed
              ∧ run QUEUED ∧ current attempt WAITING_FOR_CLAIM
              ∧ queue_deadline_at > now
              ∧ no dispatch_inbox row for the message
              ∧ now ≥ last publication + grace        (initial, or last recovery; doubling, capped)
              ∧ republications < max-publications
              ∧ lease free or expired
  for each:
     re-check eligibility now                               → cancelled / expired / delivered since: release
     verify the persisted bytes (semantic digest, identity)  → mismatch: record failure
     publish the SAME outbox row, await the broker confirm   (no transaction open)
     confirmed → record republication (claim held), next = now + interval
     failed    → record failure category, next = now + backoff
```

## What regenerates work if the broker is wiped entirely

Exactly these rows: an `outbox_messages` row of type `EXECUTION_DISPATCH` with `published_at` set and no terminal
disposition, whose `execution_dispatches` row names the current `WAITING_FOR_CLAIM` attempt of a `QUEUED`
`test_runs` row whose `queue_deadline_at` is in the future, and which has no `dispatch_inbox` row. Each is
republished — each time after a longer interval, at most `max-publications` times — until the consumer records it
or the deadline passes.

Not regenerated, deliberately:

- a dispatch the consumer already recorded — it is claimable through the API, and the broker's job is done;
- a dispatch the relay has not yet published — the relay publishes it (it never reached the broker);
- a dispatch terminally disposed by the relay (`RETRIES_EXHAUSTED`, `PERMANENT_FAILURE`, suppressed) — its own
  policies own it;
- anything whose run is cancelled, expired, or owned by a worker.

## Queries stay bounded

Eligibility is driven from `QUEUED` runs (`ix_test_runs_queue_deadline`), then each run's current attempt, the
attempt's dispatch (`uq_execution_dispatches_attempt`) and that dispatch's outbox row — never from outbox history,
which is never pruned. A pass costs in proportion to the queued runs, not to everything ever published.

## Operating notes

- **Pin `kaas.consumer.name`.** The delivered marker is keyed by it. Renaming it, or running instances under
  different names, hides earlier inbox rows: recovery then republishes (up to the cap) work already delivered, and
  the claim API no longer sees it. It must be one value, for the life of the deployment.
- **A consumer backlog is not a loss, and still costs copies.** If the consumer falls more than the grace period
  behind, recovery republishes the waiting dispatches — at most `max-publications` extra copies each, each decided
  once. Raise the grace if backlogs of that length are normal.
- **Rolling deploys.** V15 is expand-only; a previous-release instance ignores the new table (Flyway treats V15 as
  a future migration) and does not recover, so recovery is active once the new release runs.

## Guarantees

| | |
|---|---|
| Identity | same `messageId`, `dispatchId`, payload bytes and digest on every republication |
| Delivery | at least once while eligible; duplicates decided once by the inbox |
| Bound | the queue deadline; at most `max-publications` republications |
| Precedence | deadline and cancellation always win; a late copy is refused `STALE` |
| History | first publication preserved; recovery history only grows |

## Configuration

| Property | Default | |
|---|---|---|
| `kaas.dispatch.recovery.enabled` | `false`; **`true` in the production profile** | |
| `kaas.dispatch.recovery.grace` | `PT30S` | < half the queue timeout |
| `kaas.dispatch.recovery.interval` / `.initial-delay` | `PT10S` / `PT15S` | pass cadence |
| `kaas.dispatch.recovery.max-interval` | `PT2M` | spacing cap |
| `kaas.dispatch.recovery.max-publications` | `3` | per dispatch |
| `kaas.dispatch.recovery.batch-size` / `.claim-ttl` | `20` / `PT2M` | batch × confirm timeout ≤ lease; lease + grace < queue timeout, so a crashed instance's claim expires while the run can still be delivered |
| `kaas.dispatch.recovery.base-backoff` / `.max-backoff` | `PT10S` / `PT2M` | after a failed republication |

## Observability

Metrics (no run, tenant, project, message or dispatch labels): `kaas.dispatch.recovery.eligible` and
`kaas.dispatch.recovery.capped` (gauges: waiting now, and waiting with every republication spent),
`.claimed`, `.published`, `.failed{reason}`, `.skipped{reason}` (DELIVERED, RUN_NOT_QUEUED, ATTEMPT_NOT_WAITING,
QUEUE_DEADLINE_PASSED, NOT_PUBLISHED), `.claim_lost`; duplicates reaching the consumer are
`kaas.dispatch.duplicate{disposition}`. Logs carry opaque ids and failure categories, never a payload.

`/actuator/deployment` reports `dispatchRecovery` (`DISABLED`, `STARTING`, `UP`, `STALLED`) and
`dispatchesAwaitingRecovery`; a stalled loop makes the deployment NOT_READY, dispatches merely awaiting recovery
do not. Per dispatch, `dispatch_recoveries` answers: was it recovered, how many times, when, the last failure, and
— joined with `dispatch_inbox` and `test_runs` — whether it was delivered or expired at its deadline.
