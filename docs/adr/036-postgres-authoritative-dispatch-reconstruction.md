# ADR-036: PostgreSQL-authoritative dispatch reconstruction after broker loss

**Status: ACCEPTED.** Closes the deployment blocker ADR-035 measured. PostgreSQL is authoritative for queued
execution work and RabbitMQ is transport: a dispatch the broker loses after publication and before the consumer
records it is republished — the same dispatch — until it is delivered, or its run's queue deadline ends it.

## Context

KAAS-DEPLOY-001 (ADR-035 §8) measured, against a real broker:

```
published (confirmed) ─▶ lost before consumption ─▶ not rebuilt ─▶ QUEUED ─▶ QUEUE_DEADLINE ─▶ TIMED_OUT
rabbitmq_loss_recovery=false
```

Fail-closed, and the run was lost. The deployment was blocked on it. Two ways out were put to the user:
reconstruct in the application, or change the Operations durability/backup assumption. The user chose
**application-side reconstruction**. Broker durability and backup may remain as defence in depth; they are not
the correctness mechanism, because a restored broker is still a broker that lost something, and "restore the
queue" is not a guarantee the application can test.

## Decision

### 1. Recovery republishes the same logical dispatch

A recovery is another **transport delivery attempt** of one immutable dispatch, never another execution. It
publishes the outbox row the relay already published, through the same publisher: the same `messageId`, the same
payload bytes (so the same `dispatchId`, run, attempt, attempt number, snapshot, snapshot digest, queue deadline
and payload digest), the same headers, persistent delivery, the same exchange and routing key. It creates no run,
attempt, epoch, snapshot or payload, and it writes neither `execution_dispatches` nor `outbox_messages`. Before
publishing it re-verifies the persisted bytes with the relay's verifier, which re-derives the semantic digest.

This is what makes recovery safe: the consumer inbox has always deduplicated by `(consumer, messageId)` and
compared the semantic digest, so a late original and its recovery converge on **one** decision.

### 2. At least once, deliberately

Whether a message is lost cannot be known — broker depth is a transient observation, and a message may merely be
late. Recovery does not try to know. It acts on PostgreSQL alone and tolerates duplicates. The property is:
*eventually delivered while still eligible, duplicates allowed* — not exactly once. An architecture rule forbids
recovery from depending on any broker client other than the publisher interface.

### 3. Eligibility (all on the database clock)

- outbox row `EXECUTION_DISPATCH`, **published** (`published_at` not null), not terminally disposed — a message
  the relay never published is the relay's, under its own retry and dead-letter policy;
- run `QUEUED`, the dispatch's attempt is the run's current one and is `WAITING_FOR_CLAIM`;
- `queue_deadline_at` in the future;
- **no `dispatch_inbox` row** for the message under the consumer, of any disposition;
- the grace period has passed since the last publication, initial or recovery;
- not claimed by a live recovery lease; not past `max-publications`.

### 4. The delivered marker is the inbox

`dispatch_inbox` is written in the consumer's transaction before the broker is acknowledged, and its guard has
always refused `DELETE` and `TRUNCATE`. That is the durable fact "the consumer saw this message", and it is now
load-bearing: KAAS-MSG-001's tests assert the refusal, and any future retention must keep a row at least until its
run is terminal. A delivered-but-unclaimed run has an inbox row and is **never** republished — the broker did its
job; runner intake is a different concern. A run a worker owns is excluded by its attempt state even with no
inbox row.

### 5. State model: one recovery row per dispatch

Three models were evaluated:

| Model | Verdict |
|---|---|
| A. recovery columns on `outbox_messages` | Rejected. Reopens the outbox's closed guard trigger, and overloads the publication timestamps until "published" means "most recently published". |
| B. one recovery-state row per dispatch | **Chosen.** Smallest model that keeps history true. |
| C. append-only attempt log plus current state | Rejected. Diagnostics need first/last/count/last-failure, not a row per attempt. |

`dispatch_recoveries` (V15) holds, per dispatch: attempts and confirmed republications, first/last recovery,
last attempt and failure category, the next eligible instant, and a lease. `outbox_messages.published_at` stays
the first publication the broker confirmed. The table's guard trigger admits a row only for a published execution
dispatch, keeps identity immutable, lets history only grow one attempt at a time, records an attempt only as its
claim is released, and refuses delete and truncate.

### 6. Grace, cadence and bounds

- **Grace** (`kaas.dispatch.recovery.grace`, default 30 s; validated shorter than half the queue timeout): the
  legitimate interval between broker confirm and a durable consumer decision — normally one broker hop to a
  listening consumer. 30 s is a deliberately conservative engineering margin, not a measurement, for a consumer
  restart or a short backlog; it spends a tenth of the default five-minute queue budget.
- **Cadence**: a scheduled pass every `kaas.dispatch.recovery.interval` (10 s), bounded batches; no busy polling.
- **Spacing**: each republication waits twice as long as the last, capped at `max-interval` (2 min).
- **Cap**: at most `max-publications` (3) republications per dispatch — a stalled consumer accumulates at most four
  copies of a message, all decided once.
- **Failures** back off exponentially (`base-backoff` 10 s to `max-backoff` 2 min) and never terminate the run.
- **The queue deadline is the terminal authority.** Recovery never terminalizes anything.

### 7. Lease, and the order of operations

```
claim (short transaction, lease) ─▶ re-check eligibility ─▶ verify bytes ─▶ publish + await confirm (no
transaction open) ─▶ record outcome, conditional on still holding the claim
```

Concurrent instances take disjoint rows (`FOR UPDATE … SKIP LOCKED`); a crashed instance's lease expires. The
lease is taken on the claiming statement's clock and returned, and the publisher stays inside the lease the
database will enforce. Batch size × confirm timeout must fit the lease, and lease + grace must be shorter than the
queue timeout, so a crash in the confirm window cannot hold a dispatch until its run expires. Success is recorded
**only after the confirm**.

### 8. Crash windows

| Crash | Consequence |
|---|---|
| after claim, before publish | lease expires; republished later |
| after confirm, before the success write | lease expires; republished again — a duplicate the consumer absorbs |
| after the success write | nothing to do; the next republication, if any, waits a longer interval |

No path records success before the confirm, so no crash recreates the original loss.

### 9. Deadline and cancellation always win

An expired or cancelled run is never eligible, and the eligibility re-check immediately before publication makes
a cancellation or deadline that committed after the claim abort the republish. A copy that escapes just before
either is refused by the consumer, which revalidates live state (`STALE`), and the run keeps the one outcome it
already has. No attempt #2, no epoch advance: the first attempt keeps waiting, and the epoch changes only under
worker ownership.

### 10. Operations consequence

RabbitMQ state is no longer authoritative for queued execution work. PostgreSQL reconstructs a dispatch that was
published and never durably admitted by the consumer, within the bounds above. The application does **not** tell
Operations never to back up RabbitMQ; it states its guarantee, and backup policy is Operations' decision.

## Consequences

- Eligibility is driven from `QUEUED` runs, not outbox history, so a pass costs in proportion to queued work.
- `kaas.consumer.name` must be stable: the delivered marker is keyed by it.
- A consumer backlog longer than the grace republishes waiting dispatches, bounded at `max-publications` copies.
- Only execution dispatches are reconstructed. `RUN_STATE_CHANGED` has no publisher and drives nothing.
- A pre-existing residual is unchanged: a malformed body published first under a genuine message id is recorded
  `REJECTED` under its raw digest, and the genuine message is then a `CONFLICT`; recovery (like the claim API)
  treats the recorded decision as "seen" and does not republish, so that run ends at its queue deadline. Forging
  a message id requires broker publish access, which only the control plane holds.

- Duplicates of a dispatch can reach the consumer; each is an inbox no-op (`kaas.dispatch.duplicate`).
- `dispatch_recoveries` grows with recovered dispatches only, and is retained as transport evidence.
- Recovery runs in every API instance in production; `/actuator/deployment` reports it `STALLED` if an instance's
  loop stops, and READY requires it.
- A dispatch whose consumer never comes back is still lost — at its queue deadline, fail-closed — which is the
  bound, not a gap.
