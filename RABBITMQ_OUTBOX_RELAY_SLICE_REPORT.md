# Outbox Relay and RabbitMQ Publication Slice Report

## 1. Executive summary

A run now travels the whole implemented path without human intervention: `POST /runs` records a `CREATED` intent, a background scheduler performs the compare-and-set to `QUEUED` with its attempt, dispatch intent, and outbox row, and a separate relay loop publishes that message to RabbitMQ under publisher confirms, marking the outbox published only after the broker confirms.

PostgreSQL remains the source of truth for run state, attempt identity, dispatch intent, and delivery state. RabbitMQ is transport. Publication is **at least once**, with an explicit and tested duplicate window.

Nothing consumes the queue. No worker claims an attempt, no assignment epoch or lease exists, no `ExecutionCommand` is produced, and no test can execute.

## 2. Findings F-1 and F-2 resolution

**F-1 — retry and availability model.** The outbox previously had attempt counting but no notion of *when* a message next becomes eligible, so a permanently unroutable message would be reselected on every tick and per-process backoff would disagree across relay instances.

V5 adds `available_at`, `last_attempt_at`, and `terminal_disposition` alongside the existing `publish_attempts`, `published_at`, and `last_failure_code`. The partial index is now `(available_at, message_id) WHERE published_at IS NULL AND terminal_disposition IS NULL`, so selection is driven by availability with a deterministic tiebreak. Backoff doubles from a configurable base to a cap. **The database owns retry timing**; the relay process holds none.

The model supports exactly the four required states: pending, delayed retry, published, and terminal (`RETRIES_EXHAUSTED` or `PERMANENT_FAILURE`). Published and terminal are mutually exclusive, and an ended row can never be reclaimed or revised.

**F-2 — outbox semantic scope.** The choice was **generalized outbox**, per preference and because it stayed simple.

The deciding change is that `outbox_messages` now owns its own immutable `payload`, backfilled deterministically from `execution_dispatches`. That is what makes it general: the relay reads one table and never joins per message type. `dispatch_id` became nullable — an optional domain reference — with a check constraint still requiring it for `EXECUTION_DISPATCH`. The composite foreign key remains and is MATCH SIMPLE, so it is enforced whenever `dispatch_id` is present and simply inapplicable when it is not.

It is not a dumping ground: `message_type` is a controlled enum in both a check constraint and a Java enum, `schema_version` stays explicit, aggregate identity stays explicit, and the payload is bounded to 16 KB. `RUN_STATE_CHANGED` is declared with no producer and no publisher so the generalization is demonstrable rather than asserted — a test stores one with no `dispatch_id` and confirms the relay refuses to publish it rather than guessing. An undeclared type cannot be stored at all.

The honest cost: an execution dispatch payload now exists in two tables. The duplication is bounded, and the shared `payload_sha256` inside the composite foreign key keeps the copies provably consistent.

## 3. Outbox schema

| Column | Role |
|---|---|
| `outbox_id`, `message_id` | identity; `message_id` unique |
| `message_type`, `schema_version` | controlled enum + explicit version |
| `organization_id`, `project_id`, `run_id`, `aggregate_type`, `aggregate_id` | tenancy and aggregate identity |
| `dispatch_id` | **optional** domain reference; required only for `EXECUTION_DISPATCH` |
| `payload`, `payload_sha256` | immutable body and semantic digest |
| `occurred_at` | when the fact happened |
| `available_at`, `publish_attempts`, `last_attempt_at`, `last_failure_code` | delivery scheduling |
| `published_at`, `terminal_disposition` | mutually exclusive end states |
| `relay_claim_id`, `relay_claimed_at`, `relay_claim_expires_at` | relay lease |

## 4. Generalized versus specialized decision

Generalized, deliberately and with limits. The alternative — renaming it `execution_dispatch_deliveries` and staying honest about a single type — was seriously considered, but `messaging-reliability.md` already promised lifecycle events would flow through an outbox, and generalizing cost one column plus a backfill. Adding a second publishable type now requires a publisher and a verifier, not a schema change.

What was explicitly *not* done: no generic message bus, no untyped JSON envelope, no abstract publisher hierarchy, no plugin registry. The component names are `OutboxRelay`, `OutboxRepository`, `RabbitDispatchPublisher`, `PendingRunScheduler`.

## 5. Relay architecture

```
com.kaas.api.outbox.domain          OutboxMessage, MessageType, PublishOutcome/Status,
                                    FailureCode, TerminalDisposition, RetryPolicy   (java only)
com.kaas.api.outbox.application     OutboxRelay, OutboxRepository, DispatchPublisher,
                                    OutboxMessageVerifier                            (ports)
com.kaas.api.outbox.infrastructure  JdbcOutboxRepository, RabbitDispatchPublisher,
                                    RabbitTopologyConfiguration, DispatchMessageVerifier,
                                    OutboxRelayHealthIndicator                        (edges)
com.kaas.api.scheduling             the two @Scheduled triggers, nothing else
```

Rabbit-specific code lives only at the infrastructure edge. Timers live outside `..controlplane..` so the architecture rule forbidding schedulers in the control plane keeps its meaning; an ArchUnit canary now also proves those package selectors still match real classes.

## 6. Relay claim model

| Step | Transaction | Action |
|---|---|---|
| 1 | short, single statement | `UPDATE … WHERE outbox_id IN (SELECT … FOR UPDATE SKIP LOCKED ORDER BY available_at, message_id LIMIT n)` stamps a claim with an expiry and returns the batch |
| 2 | **none** | publish and await the broker confirmation |
| 3 | short, single statement | record success / retry / terminal, guarded by `AND relay_claim_id = ?` |

Chosen over lock-across-publish because holding a row lock across broker I/O pins a database connection for the duration of a network call; a hung broker would exhaust the pool and block every other relay. The indirection costs one extra statement and removes that failure mode entirely.

Claims expire, so a crashed relay strands nothing. Because step 3 is conditional on still owning the claim, a revived relay's late write is a no-op rather than a regression — tested directly.

Relay claim is unrelated to `ExecutionAttempt` worker claim. They share a word and nothing else.

## 7. RabbitMQ topology

One durable direct exchange `kaas.dispatch`, one durable queue `kaas.execution-dispatch`, one routing key `execution-dispatch`. Messages are persistent.

Deliberately absent: per-project and per-tenant queues, dynamic queue creation, topic taxonomies, priority queues, the delayed-message plugin, quorum and stream queues. None has a use case, each would be a migration to undo, and because retry timing lives in PostgreSQL no optional broker plugin is required.

Topology is declared when a connection is first established, not at startup, so a broker outage does not block the API from starting or serving reads.

## 8. Publisher confirms

Correlated publisher confirms, per message, with a configurable timeout. A normal return from `send` is never treated as success: the relay waits on the confirmation future and marks the row published only on a positive acknowledgement. NACK, confirm timeout, connection failure, and channel failure are each mapped to a bounded failure code, and an unclassified transport exception is treated as transient so the attempt budget still bounds it.

## 9. Mandatory and unroutable handling

Published with the mandatory flag and `publisher-returns` enabled, so a message no queue would accept is *returned* rather than silently discarded. The return is checked after the confirmation, because that is the order the broker produces them.

Unroutable is classified **transient-but-counted** rather than instantly permanent. This deviates from the prompt's suggested classification, deliberately: a topology declaration race on reconnect would otherwise strand a run forever, and the bounded attempt budget still ends in `RETRIES_EXHAUSTED`, so no storm is possible either way. Tested end to end by unbinding the queue from the live broker mid-suite.

## 10. Retry and backoff model

Bounded attempts (default 5). Backoff doubles from a base (default 5s) to a cap (default 5m). **No jitter**, so failure tests stay reproducible; the trade-off is that simultaneous failures retry in step, acceptable while the pending set is small and the broker is the only shared dependency. Retry delay is enforced solely by the persisted `available_at`.

This governs **broker publication only**. Execution retry and Karate scenario retry are unrelated concepts and are untouched.

## 11. Terminal publication failure

After the attempt budget is exhausted, or immediately on a permanent error, the row is marked terminal and retained — never deleted. It preserves message identity, payload, digest, attempt count, last attempt time, and a bounded failure code. No exception traces and no credentials are stored.

| Permanent immediately | Transient, bounded, then terminal |
|---|---|
| digest / integrity mismatch | broker unreachable, channel failure |
| unsupported schema version | publisher NACK |
| unsupported message type | confirm timeout |
| malformed payload | unroutable return |

This is the authoritative record of publication failure. A broker DLQ cannot serve that role, because the message never reached a queue; a future consumer DLQ is a separate concept and is not conflated with it.

## 12. At-least-once semantics

Stated plainly: publication is at least once, and nothing in the design claims otherwise. Consumption will also be at least once. There is no exactly-once anywhere — not in the relay, not in the schema, not in the documentation.

Duplicate safety rests on stable `messageId` plus the semantic payload digest, which the transport message carries in a header. The future consumer must deduplicate on identity and treat a repeated identity with a differing digest as an integrity conflict rather than a redelivery.

## 13. Crash-window analysis

| Crash point | Resulting state | Recovery |
|---|---|---|
| after claim, before publish | row claimed, unpublished | claim expires; another relay reclaims |
| after publish, before confirm observed | possibly delivered, unrecorded | confirm timeout → retry → possible duplicate |
| **after confirm, before recording success** | **delivered, unrecorded** | **republished next pass — genuine duplicate, tested** |
| during the outcome write | atomic single statement | either applied, or the claim expires |

The third row is inherent to at-least-once and is deliberately not engineered away. It is reproduced in a test against the real broker: publish, never record, wait for lease expiry, drain again, and assert the identical message with identical identity and digest is delivered twice.

## 14. Scheduler trigger

`PendingRunScheduler` finds `CREATED`, uncancelled runs ordered by `created_at, run_id` (matching the existing partial index), bounded by a configured batch size, and invokes the established `RunSchedulingService`. It adds no scheduling semantics: every compare-and-set, invariant, and database guard from ADR-017 still applies.

Each run is scheduled in its own transaction, so one failure cannot roll back the others, and a failed run simply stays `CREATED` for the next pass. Multiple API replicas are safe because the compare-and-set already resolves the race — verified with four concurrent scheduler instances.

Scanning and publishing are separate loops with separate failure domains. There is still **no public scheduling endpoint**.

## 15. Health and readiness

Readiness remains database-only. The RabbitMQ health indicator is excluded from the aggregate health status, so a broker outage cannot make `/actuator/health` return 503 and cause an orchestrator to remove a healthy control plane from service. This is verified by a suite that runs with **no broker at all** and asserts that run reads, `/actuator/health`, and `/actuator/health/readiness` all return 200.

The `outboxRelay` indicator reports pending count, terminal count, and oldest-pending age. Counts only — never broker host, virtual host, credentials, topology names, or tenant identity, asserted by the same test.

## 16. Observability

`kaas.outbox.pending` and `kaas.outbox.terminal` (gauges), `kaas.outbox.published`, `kaas.outbox.publish.failed`, `kaas.outbox.retry` (counters), and `kaas.outbox.publish.duration` (timer). Dimensions are message type and result category only — never run, project, organization, message, or dispatch identity.

Logs carry safe identifiers (`runId`, `messageId`, attempt, bounded failure code) and never the payload.

## 17. Security review

| Threat | Outcome |
|---|---|
| Credentials in code or logs | Configuration only, environment-overridable, local Compose defaults; health output asserted free of broker host, credentials, and topology |
| Broker outage causing API outage | Broker excluded from aggregate health and readiness; verified with no broker present |
| Secret / source / capability leakage | Body is the sealed DispatchIntent, which carries none; headers carry only type, version, and digest; asserted against the real delivery |
| Message headers trusted as authorization | Never read as authorization; nothing consumes them in production code |
| Cross-tenant routing | Single queue with a static routing key; nothing about routing derives from tenant input |
| Payload manipulation | Digest re-derived before publication; mismatch is permanent, never retried, never published |
| Relay publishing an unsupported schema | Version and type verified before publication; both permanent failures |
| Unbounded retries | Bounded attempt budget ending in a terminal disposition |
| Outbox amplification / massive batch reads | Bounded relay and scheduler batches, bounded payload size |
| Payload logging | Never logged, in any path |
| SQL error leakage | Database exceptions log type and SQLSTATE only, unchanged from the previous slice |

Message identity does not authenticate a producer. Transport trust will come from broker credentials and service identity in a later slice; that is stated rather than implied.

**Honest gap:** the scheduler now auto-schedules every `CREATED` run, and there is still no per-tenant quota or concurrency limit. A tenant can create runs in a loop and each becomes a queued message. The threat model lists quotas as a required control and they remain unimplemented — carried forward as a residual risk, not silently closed.

## 18. Test strategy

Real PostgreSQL 16.10 and real RabbitMQ 3.13 via Testcontainers. The broker is not mocked for primary behaviour; a stub replaces only the transport edge in the failure-injection suite, where a NACK, a confirm timeout, and an outage cannot be produced reliably against a healthy container. The relay is invoked directly rather than by its timer so each pass is deterministic, and the timers are disabled in every suite that asserts exact state.

Consumption happens only to assert what was published. No production consumer exists.

## 19. Failure-injection evidence

**Real broker:** end-to-end publication with byte-identical body, headers, and persistent delivery mode; mandatory/unroutable via live queue unbind; four concurrent relays over six messages; claim expiry with reclaim; stale-relay writes rejected; crash-after-confirm duplicate; tampered payload refused permanently; generalized outbox accepting `RUN_STATE_CHANGED` with no `dispatch_id` and the relay refusing to publish it.

**Injected at the transport edge (unit):** a NACK, an uncompleted confirmation future, a failed future, an `AmqpException`, and a mandatory return each map to the right bounded failure code; a positive acknowledgement is the only thing treated as published; the exact transport header set is asserted; and the publisher refuses to start without confirms or returns.

**Injected at the relay:** transient failure defers with strictly growing backoff and never publishes; attempt exhaustion produces `RETRIES_EXHAUSTED` and removes the row from the pending set permanently; permanent failure is terminal on the first attempt; a throwing publisher is transient rather than lost; the control plane stays available and healthy with no broker, and its health details expose backlog without broker identity.

**Fully automatic:** one suite runs with the production timers enabled and asserts that a run created over HTTP reaches `QUEUED` and then the broker with no manual call — the only test that exercises the interval properties, `@EnableScheduling`, and both `matchIfMissing` conditionals.

**Two real bugs this caught before review even started.**

The mandatory/unroutable test failed on first run with a constraint violation, and the cause was a genuine defect: `recordPublished` did not clear `last_failure_code`, so the delivery guard's success branch (and `ck_outbox_published_clean`) rejected the update. **Any message that failed even once could never be marked published** — it would republish forever.

Worse, and found only because the migration was deliberately exercised against a database that already held data: **V5's own backfill was rejected by V4's guard.** V4 raises on every outbox `UPDATE`, and the backfill is an `UPDATE`. On an empty database the statement touches zero rows and the migration is green, which is exactly what every test container and CI run does — it would have failed the first time it met production data. The guard is now dropped before the data is touched, and the upgrade path is verified against a database carrying a pre-existing V4 outbox row. The PostgreSQL reviewer independently found the same defect, which is some comfort about the class of bug but none about the process that produced it: a fresh-database migration test is not a migration test.

### What independent review changed

Five specialist reviews (distributed systems, RabbitMQ/messaging, PostgreSQL integrity, security, quality engineering) read the code and the migration rather than a summary. Two read framework and database sources directly. Every substantive finding below is fixed.

| Severity | Finding | Fix |
|---|---|---|
| P0 | V5's backfill was rejected by V4's guard — the migration could only ever apply to an empty database | Guard dropped before the backfill; upgrade-with-data path verified |
| P0 | A ~75-second retry budget meant a routine broker restart terminally dead-lettered **every** pending dispatch, and terminal was irreversible by construction | Budget raised to ~50 minutes; an audited `TERMINAL → pending` requeue transition added to the guard so a dead letter is recoverable without a migration |
| P0 | `batch-size × confirm-timeout` (500s) could exceed `claim-ttl` (60s), so a batch outlived its own lease. Duplicates multiplied, and the lost-claim path returned silently without advancing `available_at` or `publish_attempts` — a hot loop with no backoff and no attempt accounting | Startup cross-validation of the three values; the batch now stops before its lease expires and **releases** its remaining rows; every lost claim is logged and counted |
| P1 | `ck_outbox_claim_shape` was fail-open on a NULL `relay_claim_expires_at` (`FALSE OR NULL` is NULL, and a CHECK only rejects on FALSE), producing a lease that never expires on a row that can never be ended | `IS NOT NULL` added to the disjunct |
| P1 | The verifier deserialized a *projection* while the publisher sent the raw bytes; Jackson 3 defaults `FAIL_ON_UNKNOWN_PROPERTIES` to false, so unknown JSON keys rode to the broker under a "verified" digest header | Strict deserialization; `projectId` and `dispatchId` also bound |
| P1 | An unauthenticated `/actuator/health` ran an unindexed `count(*)` over a table that can never be pruned | Partial index on the terminal predicate; `show-details` pinned |
| P1 | The relay and the run scheduler shared Spring's single default timer thread, so broker latency stalled `CREATED → QUEUED` | Pool size raised; connection timeout set so a blackholed broker fails fast |
| P1 | `RabbitDispatchPublisher` had **no direct test**: NACK, confirm timeout, connection failure, and the constructor guards were entirely uncovered. Deleting the `ack()` check would have left every test green | `RabbitDispatchPublisherTest` covers all of them with a mocked template |
| P1 | Nothing asserted that publisher confirms were actually enabled. A missing or misspelled property leaves the confirmation future uncompleted forever — every message times out and the backlog dead-letters, with a DEBUG log as the only signal | The publisher refuses to start unless confirms and returns are enabled |
| P1 | A deterministically failing run sat at the head of the `created_at` batch forever and starved all scheduling | Bounded per-instance cooldown; durable per-run backoff documented as deferred |
| P1 | The failure suite had no isolation and asserted against global counts; the F-4 replay change was vacuous because the timers were disabled; no suite ran with the production timers on | Per-test reset, run-scoped assertions, a replay-after-scheduling test, and a suite that runs the timers for real |
| P2 | The guard delegated the claim protocol to the repository's `WHERE` clauses: a live lease could be stolen, a backed-off row claimed early, and an outcome recorded against an unclaimed row | All three predicates moved into the guard |
| P2 | The outbox payload was bound to its dispatch only by digest, so a divergent body was accepted and then permanently dead-lettered at relay time | Bundle invariant now compares the payloads |
| P2 | The committed local broker password and TLS-off default failed **open** for a remote host | Startup refuses a non-loopback broker with the local password or without TLS |
| P2 | `RETRIES_EXHAUSTED` was logged as "will not be published", but a confirm timeout may already have been delivered | Wording corrected; delivery-unknown documented for a future reconciler |
| P2 | No jitter, contradicting `messaging-reliability.md` | Bounded jitter added, configurable, default 25% |
| P2 | Concurrency and message-type tests passed for the wrong reason; claim-expiry tests raced a 1-second TTL | Small batch size, deterministic lease expiry, `INSERT`-based constraint assertions |
| P3 | Sub-second `claim-ttl` truncated to zero and bricked the relay; an interrupt burned an attempt on every remaining message; a mid-batch exception abandoned the rest; `content-encoding` misused as a charset; empty-string TLS defaults changed the configuration path; nack reason discarded | All fixed |

Findings deliberately **not** acted on: adding durable per-run scheduling backoff (a `test_runs` schema change the claim slice will revisit), and re-serializing the payload at publish time instead of sending stored bytes (strict deserialization closes the same hole without changing what the consumer receives).

## 20. Files changed

**Added** — `V5__outbox_relay_delivery.sql`; `BrokerCredentialGuard`; `RabbitDispatchPublisherTest`; `AutomaticDispatchIntegrationTests`; `com.kaas.api.outbox.domain` (7 types); `com.kaas.api.outbox.application` (`OutboxRelay`, `OutboxRepository`, `DispatchPublisher`, `OutboxMessageVerifier`); `com.kaas.api.outbox.infrastructure` (`JdbcOutboxRepository`, `RabbitDispatchPublisher`, `RabbitTopologyConfiguration`, `DispatchMessageVerifier`, `OutboxRelayHealthIndicator`); `com.kaas.api.scheduling` (3 classes); `PendingRunScheduler`; `SchedulableRun`; `OutboxRelayRabbitIntegrationTests`; `RelayFailureAndSchedulerIntegrationTests`; `RetryPolicyTest`; ADR-018; `docs/architecture/outbox-relay-rabbitmq-slice.md`; this report.

**Modified** — `build.gradle.kts` (AMQP added; RabbitMQ removed from the forbidden list, everything else still banned); `application.properties` (relay, scheduler, broker, health); `JdbcRunSchedulingRepository` (outbox insert carries payload and availability; `findSchedulable`); `RunSchedulingRepository`; `RunIntentService` (F-4); `ControlPlaneArchitectureTest` (domain rule + canary); the three pre-existing integration suites (timers disabled, migration chain now 1–5, changed guard messages); `openapi-v1.yaml`; `README.md`; `IMPLEMENTATION_STATUS.md`; `docs/adr/README.md`.

## 21. Verification

| Gate | Result |
|---|---|
| `./gradlew clean check` | **PASS — 83 tests (82 API + 1 runner), 0 failures/errors/skips** |
| PostgreSQL + RabbitMQ Testcontainers | PASS, both real |
| `npm --prefix packages/api-contracts test` | PASS |
| `npm --prefix packages/api-contracts run lint:openapi` | PASS |
| web lint / typecheck / test / build / audit | PASS, 0 vulnerabilities |
| `docker compose … config` | PASS |
| `git diff --check` | PASS |
| Runtime dependencies | AMQP/RabbitMQ present as intended; **0** hits for Karate, MinIO, docker-java, Testcontainers, and every secret-provider SDK |

## 22. Residual risks

- **No consumer.** The queue accumulates published messages nothing reads. Intended, but it means messages are now durable in two places with no drain.
- **No outbox retention.** Published and terminal rows are kept forever. A retention or archival policy is needed before this runs long.
- **No quota.** Auto-scheduling plus unbounded run creation is an amplification path with no per-tenant limit.
- **Requeue is database-only.** The guard now supports an audited `TERMINAL → pending` transition, but no application code or operator tool exposes it, so recovery is a hand-written statement. That is deliberate for this slice; the relay slice's successor should give it a real command.
- **No durable per-run scheduling backoff.** A deterministically failing run is skipped by a per-instance cooldown, which is memory, not state. A restart retries it immediately. Durable backoff needs a `test_runs` schema change and belongs with the claim slice's guard rewrite.
- **Backoff has no jitter.** Fine now; revisit when the pending set is large enough for synchronized retries to matter.
- **Duplicate publication is real.** Correct and intended, but it is only safe once a consumer deduplicates; nothing enforces that yet because no consumer exists.
- **Payload duplication.** The dispatch body lives in both `execution_dispatches` and `outbox_messages`, kept consistent by the shared digest in the composite foreign key rather than by a single source.

## 23. Required changes before worker claim

These are intact and must be rewritten **together**, as one migration, by the claim slice:

- `require_complete_scheduling_bundle` — its `ELSIF scheduling_children <> 0` branch rejects every transition *out* of `QUEUED`, because the attempt row still exists.
- `guard_initial_execution_attempt` — requires the run's `queued_at` to equal the attempt's `created_at`, which attempt #2 can never satisfy.
- `ck_run_lifecycle_events_schedule` — pins `sequence = 1` and `CREATED → QUEUED`.
- `uq_execution_attempts_one_per_run`, `ck_execution_attempts_initial_number`, `ck_execution_attempts_initial_state`, `ck_execution_dispatches_attempt_number`.

They are deliberately not weakened now.

## 24. Recommended next slice

**Consumer inbox and worker claim**, in that order, and probably as two slices.

The inbox comes first and is small: a consumer that deduplicates on `messageId`, treats a repeated identity with a differing digest as an integrity conflict, and records processing in the same transaction as its effects. It needs no capability and no execution authority, so it keeps the hostile-execution gate closed while making the duplicate window this slice created provably safe.

**Worker claim** follows and is the larger, riskier one: assignment epoch, lease, fencing, the guard rewrite listed in §23, and only then `ExecutionCommand` production. It should not be combined with the inbox, and it must not be combined with runner execution — KAA-004, the hostile-execution boundary, is still open and remains the gate before anything actually runs a test.
