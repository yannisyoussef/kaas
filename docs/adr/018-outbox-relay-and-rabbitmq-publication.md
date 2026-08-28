# ADR-018: Relay the outbox to RabbitMQ with at-least-once publication and database-owned retry

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** Publication of already-durable outbox messages to RabbitMQ, the delivery-scheduling model that governs it, the relay claim protocol, and the production trigger that moves runs `CREATED → QUEUED`. No consumer, no worker claim, no execution.

## Context

ADR-017 left the system able to record a durable `DispatchIntent` and nothing else. Two review findings blocked transport:

**F-1.** The outbox had `publish_attempts` and `last_failure_code` but no notion of *when* a message next becomes eligible. A permanently unroutable message would be reselected on every relay tick at full speed, and a relay holding backoff in memory would disagree with a second relay instance — the same argument that rejected application locking for scheduling.

**F-2.** The outbox had no payload of its own. Its body lived in `execution_dispatches`, so it was structurally an execution-dispatch delivery table wearing a general name. `messaging-reliability.md` promised lifecycle events would flow through an outbox; that was impossible.

Separately, scheduling was implemented but untriggered: no timer, no relay, no endpoint, so a deployed run stayed `CREATED` forever.

## Decision

### The outbox is generalized, but explicitly typed

`outbox_messages` gains its own immutable `payload`, backfilled deterministically from `execution_dispatches`. The relay reads one table and never joins per message type. `dispatch_id` becomes an optional domain reference, kept `NOT NULL`-equivalent for execution dispatch by a check constraint, and the composite foreign key still binds identity, tenancy, and payload digest to the dispatch row whenever it is present (MATCH SIMPLE means it is simply not enforced when the column is null).

`message_type` is a controlled enum — `EXECUTION_DISPATCH` and `RUN_STATE_CHANGED` — enforced by a check constraint and mirrored by a Java enum. `RUN_STATE_CHANGED` has no producer and no publisher; it exists so the generalization is demonstrable rather than asserted, and the relay refuses to publish it rather than guessing. This is not a generic untyped dumping ground: an undeclared type cannot be stored at all.

The cost is that an execution dispatch payload exists twice, in `execution_dispatches` and in `outbox_messages`. That duplication is bounded (16 KB), and the shared `payload_sha256` inside the composite foreign key keeps the two copies provably consistent.

### PostgreSQL owns retry timing

`available_at`, `publish_attempts`, `last_attempt_at`, `published_at`, `terminal_disposition`, and `last_failure_code` live in the row. Selection is driven by `available_at` with `message_id` as a deterministic tiebreak, over a partial index restricted to rows that are neither published nor terminal. Backoff doubles from a base to a cap, with bounded jitter so a backlog that all failed in the same tick does not re-converge on a single instant.

The attempt budget must span a routine broker restart or failover. A short budget is not a conservative choice but a destructive one: it would dead-letter every pending dispatch during ordinary maintenance. The default is roughly fifty minutes.

Publication is a final state. A terminal disposition is **not**: the guard supports an explicit `TERMINAL → pending` requeue that resets the attempt budget and clears the failure code. Without it, immutability would be an operational trap — a broker outage would strand runs permanently with no recovery short of a migration. It is deliberately unreachable from application code, so a requeue is always a decision.

### Claim and lease, not lock-across-publish

A relay claims a bounded batch in one statement using `FOR UPDATE SKIP LOCKED`, publishes **outside any transaction**, then records the outcome in a second short statement guarded by `AND relay_claim_id = ?`.

A batch must never outlive its own lease. `claim-ttl` is cross-validated at startup against `batch-size × confirm-timeout`, and the relay also stops mid-batch when the remaining lease no longer covers another publish, releasing the rows it has not touched. Without both, a slow-but-alive broker lets a second relay reclaim rows the first is still publishing, and the loser's outcome write is rejected — so duplicates multiply while the attempt budget silently never advances. Every lost claim is logged and counted for exactly that reason.

The claim protocol lives in the guard, not only in the repository's `WHERE` clauses: a live lease cannot be stolen, a backed-off row cannot be claimed early, and an outcome cannot be recorded against an unclaimed row. Leaving those to application SQL would contradict this ADR's own premise that delivery scheduling is owned by the database.

No database transaction is ever held open across broker network I/O, so a hung broker cannot pin connections or block other relays. `SKIP LOCKED` makes concurrent relays take disjoint work instead of queueing behind each other. Claims expire, so a crashed relay's rows become reclaimable; and because every outcome write is conditional on still owning the claim, a revived relay cannot overwrite a newer disposition.

Relay claim is unrelated to `ExecutionAttempt` worker claim. They share a word and nothing else.

### Published means confirmed

A normal return from `send` means nothing. A message is marked published only after a correlated publisher confirmation.

The publisher refuses to start unless the connection factory actually has confirms and returns enabled. If correlated confirms are misconfigured, Spring never completes the confirmation future: every message would wait out its timeout and the entire backlog would dead-letter, with a DEBUG line as the only clue. The whole reliability story rests on that setting, so it is asserted rather than assumed. Messages are persistent and published with the mandatory flag, so one that reaches no queue is *returned* rather than silently discarded, and returns are checked after the confirmation because that is the order the broker produces them.

Unroutable is treated as retryable-but-counted rather than instantly terminal. The prompt's framing suggests it is definitively a configuration error, and usually it is — but a topology declaration race on reconnect would otherwise strand a run permanently. Bounded retries end in `RETRIES_EXHAUSTED`, so no storm is possible either way.

### Publication is at least once, and the duplicate window is not "solved"

The relay may publish, receive a confirmation, and crash before recording success. The next pass republishes. This is inherent, expected, and deliberately left alone: stable `messageId` plus the semantic payload digest are what make a duplicate safe for the future consumer. Nothing in the design assumes exactly-once publication, delivery, or consumption.

### Fail closed before the broker

The relay re-derives the semantic digest from the persisted payload and refuses to publish on mismatch. Deserialization is strict: the digest covers only the fields the contract declares, but the relay publishes the stored bytes, so an unknown key would otherwise travel to the broker unverified under a digest header that claims to cover it. That is a permanent failure, recorded immediately without retry, because a durable record that no longer matches its digest is a security event rather than a hiccup. Unsupported schema version, unsupported message type, and malformed payload are permanent for the same reason.

Terminal messages are retained, never deleted. This relay-side disposition is the authoritative record of publication failure; a future consumer dead-letter queue is a different concept and must not be conflated with it.

### The broker is not the source of truth, and it cannot take the API down

PostgreSQL remains authoritative for run state, attempt identity, dispatch intent, and delivery state. The RabbitMQ health indicator is excluded from the aggregate health status, and readiness remains database-only, so a broker outage cannot cause an orchestrator to remove a healthy control plane from service. Backlog is observable through `outboxRelay` health details and `kaas.outbox.*` metrics instead.

### Scheduling gets a production trigger, in its own loop

`PendingRunScheduler` finds eligible `CREATED` runs in a bounded, deterministically ordered batch and invokes the existing `RunSchedulingService` use case, so every established invariant, compare-and-set, and database guard still applies. Each run is scheduled in its own transaction, so one failure cannot roll back the others, and multiple API replicas are safe because the compare-and-set already resolves the race.

Scanning and publishing are deliberately separate loops. Combining them would put broker latency inside a database transaction and couple two different failure domains. There is still no public scheduling endpoint.

Timers live in `com.kaas.api.scheduling`, outside `..controlplane..`, so the architecture rule forbidding schedulers in the control plane keeps its meaning.

### Minimum topology

One durable direct exchange, one durable queue, one routing key. No per-tenant or per-project queues, no dynamic queue creation, no topic taxonomy, no priority queues, no delayed-message plugin, no quorum or stream queues. None has a use case, and each would be a migration to undo. Because retry timing lives in PostgreSQL, no optional broker plugin is required.

## Alternatives considered

### Keep the dispatch-specific delivery table

Rejected. It cannot carry a second durable fact, and the architecture documentation already promised one. Naming it honestly was the alternative; generalizing it was cheap enough to prefer.

### Hold the row lock across the publish

Rejected. It is simpler, but it pins a database connection for the duration of broker I/O, and a hung broker would exhaust the pool and block every other relay. The claim/lease indirection costs one extra statement and removes that failure mode.

### Broker-native delayed retry

Rejected. It requires an optional plugin, moves retry state into the broker, and would make the database no longer authoritative for delivery. `available_at` achieves the same thing with primitives already present.

### Rely on a broker dead-letter queue for publication failure

Rejected as a category error. The message never reached a queue, so no broker mechanism can record the failure. The outbox's terminal disposition is the only place that can.

### Omit jitter for test determinism

Rejected on review. `messaging-reliability.md` specifies jitter for this failure class, and without it an outage-induced backlog all becomes claimable in the same instant. Jitter is bounded and additive, so backoff still grows strictly with each attempt and failure tests stay meaningful.

## Consequences

- A run now reaches the broker end to end without human intervention.
- Duplicate publication is possible and expected. The future consumer must deduplicate on `messageId` and treat a repeated identity with a differing digest as an integrity conflict rather than a redelivery.
- The outbox grows without bound: published and terminal rows are retained as evidence with no retention policy yet.
- A terminal message needs an operator. There is no requeue path, deliberately, because reviving a message is a decision, not an automatic behaviour.
- Adding a second publishable message type requires a publisher and a verifier, not a schema change.

## Constraints the worker-claim slice must rewrite

Left intact here, and documented so the next slice does not discover them by failing:

- `require_complete_scheduling_bundle` — its `ELSIF` branch rejects any transition *out* of `QUEUED`, because the attempt row still exists.
- `guard_initial_execution_attempt` — requires the run's `queued_at` to equal the attempt's `created_at`, which attempt #2 can never satisfy.
- `ck_run_lifecycle_events_schedule` — pins `sequence = 1` and `CREATED → QUEUED`.
- `uq_execution_attempts_one_per_run`, `ck_execution_attempts_initial_number`, `ck_execution_attempts_initial_state`, `ck_execution_dispatches_attempt_number`.

These must be rewritten together, as a unit. They are not weakened now.

## Verification

PostgreSQL 16.10 and RabbitMQ 3.13 Testcontainers cover the end-to-end flow, publication only after a positive confirm, mandatory/unroutable returns via a live unbind, four concurrent relays, claim expiry and stale-relay no-op, crash-after-confirm duplicate publication, tampered-payload refusal, and the generalized outbox accepting a second message type with no dispatch reference. Failure injection covers transient retry with growing backoff, exhaustion to terminal, permanent failure, a throwing publisher, and the control plane staying available and healthy with no broker present. Scheduler tests cover batch bounds, ordering, multi-instance safety, and refusal to touch a non-`CREATED` run.
