# Run Scheduling, Execution Attempt, and Transactional Outbox Slice Report

## 1. Executive summary

KaaS now implements exactly one runtime lifecycle transition: `CREATED → QUEUED`. A single PostgreSQL transaction performs a database-level compare-and-set on the run, creates `ExecutionAttempt` #1, seals an immutable queue-time `DispatchIntent` bound to the existing sealed `RunSnapshot`, records the lifecycle event, and writes exactly one unpublished outbox message. `QUEUED` therefore provably means durable execution intent exists.

Nothing publishes to a broker, claims an attempt, mints an assignment epoch or lease, issues a secret or source capability, produces an `ExecutionCommand`, or executes anything. Scheduling is an internal application use case with no public endpoint.

This work began as another agent's uncommitted, unfinished slice. It was recovered rather than rewritten.

## 2. Recovery of interrupted work

The working tree carried **two** uncommitted slices, not one: the TestRun intent slice (Flyway V3, `RunController`, `RunIntentService`) had never been committed either, despite the handover describing it as a completed milestone. `HEAD` was `944b65b feat: add immutable execution configuration`.

A textual and file-level backup of the entire uncommitted state was taken before any edit. No destructive Git operation was run at any point; nothing was reset, stashed, cleaned, checked out, or committed.

Classification of the inherited work:

| Category | Findings |
|---|---|
| **A — complete and consistent** | Scheduling domain model (`TestRun.queued`, `ExecutionAttempt`, `ExecutionDispatch`, `ExecutionDispatchPolicy`, `ScheduleDisposition`), `RunSchedulingService`, `JdbcRunSchedulingRepository`, the bulk of Flyway V4, `execution-dispatch.schema.json` with nine fixtures, the `runner-command.schema.json` protocol correction, the ArchUnit scheduling/process guard, and the Testcontainers runtime-dependency ban |
| **B — partial, correct direction** | Contract validator wiring for the dispatch schema; OpenAPI run documentation |
| **C — placeholder/temporary** | An unused `ACTOR` constant in `RunSchedulingService` |
| **D — contradicts architecture** | None found |
| **E — required investigation** | Two latent defects, below |

The inherited code **compiled cleanly**, and V1→V4 **applied cleanly**, which is why the defects had survived: both were latent.

**Defect 1 — `jsonb_object_length` does not exist in PostgreSQL 16.** V4's `guard_execution_dispatch` trigger called it to assert the payload's field count. PL/pgSQL resolves function references at execution time, not creation time, so the migration applied without complaint and the trigger would have raised `function jsonb_object_length(jsonb) does not exist` on the *first dispatch insert* — that is, on every scheduling attempt. Confirmed empirically against `postgres:16.10-alpine`, both by inspecting `pg_proc` and by executing the original expression. Replaced with `(SELECT count(*) FROM jsonb_object_keys(NEW.payload))`.

**Defect 2 — the contract validator was syntactically live but semantically dead.** Three dispatch assertions had been appended *after* the final function in `validate-schemas.mjs`, landing at module top level outside `validateSemanticInvariants`, where `dispatch` is not in scope. `npm test` failed with `ReferenceError: dispatch is not defined`. The assertions were moved into the function and extended.

Both are classic interruption artifacts: an edit begun and not landed. Neither was a design error, so in both cases the previous agent's intent was completed rather than replaced.

**A third class of defect was found only by adversarial review**, after the slice was already green. Five specialist reviews read the diff rather than a summary, and the PostgreSQL reviewer demonstrated four working exploits against a live database: the dispatch payload guard **failed open**. Written as `IF <something is wrong> THEN RAISE` over `<>` comparisons, it accepted any payload with an absent or JSON-null field, because `payload->>'absent'` is SQL NULL and `false OR NULL` is NULL — so the `IF` was never taken. The worst case dropped `runSnapshotDigest` entirely and added an attacker-chosen key while keeping the field count at sixteen, and the database certified it as "exactly matching its trusted semantic columns". Section 17 lists the full set of review findings and their fixes.

## 3. Protocol correction

The architecturally important distinction — and the one the inherited work had already got right — is that **queue-time dispatch intent is not claim-time execution command**.

```
Scheduler ──▶ DispatchIntent ──▶ (future broker) ──▶ (future worker claims attempt)
                                                            │
                                        assignment epoch ───┤
                                        active lease ───────┤
                                        source capability ──┤
                                        secret capability ──┘
                                                            ▼
                                                    ExecutionCommand ──▶ (future runner)
```

At scheduling time no worker exists, so no assignment, lease, or capability can truthfully exist. Writing them would mean minting security capabilities before there is a bearer and persisting them, unexpired, in a durable message.

`runner-command.schema.json` was retitled to state that it is claim-time only and never produced by scheduling. That correction was inherited and preserved. This slice produces no `ExecutionCommand`.

The boundary is enforced, not merely documented: the dispatch schema is closed (`additionalProperties: false`) over exactly sixteen identity fields, the database has no columns for claim-time authority, a trigger requires the stored payload to match its semantic columns field by field, a contract fixture asserts a forbidden capability field is rejected, the validator asserts sixteen named claim-time fields are absent, and an integration test asserts the persisted payload contains none of twenty-two forbidden tokens.

## 4. `CREATED → QUEUED` transition

| | before | after |
|---|---|---|
| `lifecycleState` | `CREATED` | `QUEUED` |
| `runVersion` | 1 | 2 |
| `queueStartedAt` | null | server clock |
| `queueDeadlineAt` | null | `queueStartedAt + kaas.scheduling.queue-timeout` |
| ETag | `"run-1"` | `"run-2"` |

No other transition is implemented. `QUEUED → CLAIMED` and everything beyond remain defined in the state machine and unreachable from any code.

## 5. ExecutionAttempt

Scheduling creates attempt #1 in state `WAITING_FOR_CLAIM`, representing infrastructure history rather than run lifecycle. There is no assignment epoch — not set to a placeholder, but structurally absent: `execution_attempts` has no column for one, and a test asserts the table's exact column set. `ExecutionAttemptState` has exactly one constant, so no claimed or assigned state can be reached.

`uq_execution_attempts_one_per_run` plus `attempt_number = 1` checks pin the MVP to a single attempt. Infrastructure retry (attempt #2) requires dropping them deliberately as a schema change. Execution attempt is not Karate scenario retry; the latter lives in the snapshot's `scenarioRetry` and is unrelated.

## 6. DispatchIntent

Sixteen fields, closed: schema version, message ID, message type, dispatch ID, `occurredAt`, producer, organization, project, run, `runVersion`, attempt ID, attempt number, snapshot ID, snapshot digest, `queueDeadlineAt`, payload digest.

It references the already sealed `RunSnapshot` by identity and digest rather than copying execution configuration, so it carries no feature source, secret reference, secret value, capability, object-store URL, Docker setting, routing key, worker identity, assignment epoch, or host path.

## 7. Queue timing

Queue timing begins only at `QUEUED`, preserving the correction ADR-016 made when it removed `queueDeadlineAt` from `CREATED`. Both timestamps are server owned, read from the database clock via `clock_timestamp()`. Clients cannot supply or influence them: scheduling accepts no request body, and the timeout is configuration (`kaas.scheduling.queue-timeout`, default `PT5M`) with a validated range of one nanosecond to 24 hours. A check constraint requires `queue_deadline_at > queued_at`, and the V3 constraint requiring `CREATED` to have no queue timing still holds.

## 8. Transactional outbox

One transaction writes the run transition, the attempt, the dispatch, the lifecycle event, and the outbox row. The outbox row is left unpublished — `published_at` null, `publish_attempts` zero, `last_failure_code` null — pinned by a check constraint so no code can pretend to have published. Immutable message identity and content are separated from the mutable delivery metadata a future relay will own. A partial index on unpublished rows ordered by `occurred_at, message_id` is the future relay's claim path.

Nothing reads or publishes it. No consumer exists, so no inbox is implemented; ADR-013 continues to hold that requirement.

## 9. Atomicity

The invariant is `QUEUED ⇒ a durable attempt, dispatch, lifecycle event, and unpublished outbox message all exist`, and its converse: a failed transaction leaves the run `CREATED` with none of them.

It is enforced by the database, not trusted to the application. Deferred constraint triggers on all five tables re-check at commit that a `QUEUED` run has exactly one complete bundle whose digests, versions, and timestamps agree, and that scheduling children never exist for a non-`QUEUED` run. Inserting an attempt for a `CREATED` run is rejected.

## 10. Optimistic concurrency

`SELECT … FOR UPDATE` on `(organization_id, run_id, lifecycle_state = 'CREATED', cancellation_status = 'NOT_REQUESTED', run_version = expected)`, followed by a conditional `UPDATE` on the same predicate that fails the transaction if it does not affect exactly one row.

Concurrency is resolved by PostgreSQL row locking and re-qualification only. There is no `synchronized`, no JVM lock, no application singleton — none of which survive a second control-plane instance. An ArchUnit rule additionally forbids the control plane from depending on `ProcessBuilder`, `Runtime`, `@Scheduled`, `TaskScheduler`, or `ScheduledExecutorService`.

## 11. Duplicate scheduling

Scheduling is idempotent by state and invariants rather than by a client-supplied key, which it does not accept. A repeat against an already-`QUEUED` run returns `ALREADY_SCHEDULED` and performs no durable work — no new attempt, dispatch, outbox row, or queue deadline. A stale expected version returns `STALE_VERSION` without transitioning. Ten concurrent schedulers produce exactly one `SCHEDULED` and nine `ALREADY_SCHEDULED`.

## 12. Persistence model

Flyway V4 adds `execution_attempts`, `execution_dispatches`, `run_lifecycle_events`, `outbox_messages`, and `test_runs.current_attempt_id`.

V3 made every `test_runs` update fail closed. V4 **narrows** rather than removes that guard: a trigger permits exactly the `CREATED → QUEUED` shape — `run_version` incremented by one, coherent queue timing, an attached attempt, the scheduler actor, and, enforced by a `to_jsonb` difference over all remaining columns, no other change. Every other lifecycle mutation and every delete still fail closed.

Attempts, dispatches, lifecycle events, and outbox messages are insert-only; `UPDATE` and `DELETE` are rejected outright. Composite foreign keys bind organization, project, run, and attempt together, so cross-tenant rows cannot be forged.

## 13. Contract changes

`packages/api-contracts/execution-dispatch.schema.json` is a strict, closed Draft 2020-12 schema with nine fixtures: canonical, minimal, and seven negatives covering missing identity, wrong schema version, wrong message type, a forbidden capability field, a malformed digest, a non-initial attempt number, and a malformed deadline. Each negative asserts the specific keyword that must reject it. The validator additionally asserts chronology, snapshot identity binding, attempt number, post-transition run version, and the absence of sixteen claim-time authority fields.

`runner-command.schema.json` is retitled as claim-time only. OpenAPI's `Run` schema gained `queueStartedAt` and `queueDeadlineAt` — a genuine pre-existing mismatch, since the implementation already serialized them while the schema declared `additionalProperties: false` without them — and the `RunEtag` header now documents `"run-1"` and `"run-2"`. No scheduling endpoint was added.

## 14. Source boundary

Untouched and unchanged. The dispatch references the sealed snapshot by identity and digest; it carries no feature source, no source bundle, no object-store reference, and no source capability. Nothing in this slice reads feature source bodies.

## 15. Secret capability boundary

Untouched and unchanged. Secret bindings remain metadata-only `{key, secretReferenceId}` inside the snapshot. The dispatch carries neither secret references nor secret values, and no capability is minted, stored, or transmitted. Secret redemption remains unimplemented, and no runtime dependency on any secret provider exists.

## 16. Network-policy boundary

Unchanged and still absent. No egress policy, allowlist, or network placeholder is persisted or transmitted. Network enforcement belongs to the hostile-execution gate (KAA-004), which remains open; this slice does not weaken it.

## 17. Security review

| Threat | Outcome |
|---|---|
| Duplicate scheduling | Compare-and-set plus `uq_execution_attempts_one_per_run`; ten-scheduler race yields one winner |
| Forged attempt identity | Composite FK to run/org/project; deferred bundle trigger requires the attempt to match its exact `QUEUED` run; direct insert for a foreign org rejected |
| Stale `runVersion` | Predicate on expected version; returns `STALE_VERSION` with no writes |
| Cross-tenant scheduling | Organization predicate in lock, update, and read; foreign and unknown runs both conceal as 404 with no existence oracle |
| Dispatch tampering | `UPDATE`/`DELETE` rejected; payload must match trusted semantic columns; digest conflict on the same message identity rejected |
| Premature assignment epoch | Structurally impossible — no column, no field, no state |
| Runtime capability persisted too early | Closed schema, no columns, forbidden-field fixture, validator assertion, and payload token assertion |
| Source content leakage | Dispatch references the snapshot by digest only |
| Secret reference/capability leakage | No secret field in the dispatch or its tables |
| User-controlled queue deadline | Server clock plus server configuration; no client input path exists |
| Lifecycle guard bypass | V4 guard permits exactly one shape and compares all other columns via `to_jsonb` difference |
| Outbox payload in logs | `RUN_QUEUED` logs only run, project, organization, attempt, and version identifiers |

### Defects found by independent review, and their fixes

| Severity | Finding | Fix |
|---|---|---|
| Critical | Dispatch payload guard **failed open** on absent/JSON-null fields and on dropped-key/added-key substitution at equal cardinality (four working exploits demonstrated) | Rewrote as fail-closed `IF <all checks pass> THEN RETURN NEW`, wrapped in `coalesce(…, false)`; assert the exact key set, not the count; check JSON scalar types |
| High | Application clock (run creation) vs database clock (scheduling) could trip the `updated_at` monotonicity guard on ordinary NTP drift, rejecting valid transitions until wall time caught up | Queue start clamped never to precede the run's own last update |
| High | The `QUEUED` bundle invariant read the outbox's mutable delivery columns, so a future relay's legitimate publish would break unrelated run updates | Invariant now asserts existence, identity, digest, and timestamp agreement only |
| High | Deferred bundle triggers were entirely unexercised — all five could be deleted with the suite green | Added a commit-time partial-bundle test |
| High | Every database negative assertion checked only the exception type, and all guards share SQLSTATE 23514 | Every rejection now asserts its own guard's message |
| Medium | Update guard required `cancellation_status` *unchanged* rather than `NOT_REQUESTED`, so a cancelled run could be queued by direct statement | Added `OLD.cancellation_status = 'NOT_REQUESTED'` |
| Medium | Digest used `Instant.toString()`, whose fractional precision varies, making it reproducible only in Java | Normalized to six fractional digits; rules and a frozen vector published in the contracts README |
| Medium | Dispatch payload serialized with the shared web `ObjectMapper`; an unrelated `spring.jackson.*` change could break scheduling at runtime | Private `JsonMapper` owned by the scheduling module |
| Medium | Database exception messages (`SQL [...]` plus PostgreSQL trigger text) were logged via the exception cause | Database exceptions log type and SQLSTATE only; no response change |
| Medium | Outbox `occurred_at` was unconstrained despite being the future relay's ordering key | Bound to the dispatch in the bundle invariant |
| Medium | Payload timestamp validation depended on the session `TimeZone` for offset-less strings | Explicit offset now required |
| Medium | `ALREADY_SCHEDULED` ignored the expected version entirely | Returns `STALE_VERSION` unless the caller's expectation matches this transition |
| Medium | Concurrency test's disposition counts were also satisfiable by serial execution; Hikari pool exactly equalled the scheduler count | Assert real time overlap; raise the pool above the scheduler count |
| Low | `registerSynchronization` throws with no active synchronization, letting logging fail the use case | Guarded by `isSynchronizationActive()` |
| Low | Row triggers do not fire for `TRUNCATE` | Statement-level guards on all six evidence tables |
| Low | `ExecutionDispatch` had no invariants, so `digest()` could NPE on a deserialized message | Compact constructor rejects incomplete identity |
| Low | `hex()` sliced the `sha256:` prefix without checking it; `catch (Exception)` around serialization | Prefix asserted; catch narrowed to `JacksonException` |
| Low | ArchUnit rules use `allowEmptyShould(true)`, so a mistyped selector would pass silently | Added a canary asserting the selectors match classes |

Logging deliberately excludes the dispatch body, snapshot configuration, feature source, secret references, JWT, idempotency key, and outbox payload. The log is registered as an `afterCommit` synchronization, so a rolled-back scheduling attempt never emits a `RUN_QUEUED` event.

Review found and this slice fixed one further leak on a path this slice makes more reachable: the generic exception handler attached the exception cause for database failures, and Spring composes those messages as `<task>; SQL [<statement>]; <server message>` — so a lock timeout or deadlock would have written the statement and the PostgreSQL trigger text into the error log. Database exceptions now log their type and SQLSTATE with no cause. No client-visible response changed.

## 18. Testing and concurrency evidence

`./gradlew clean check` — **52 tests, 0 failures, 0 errors, 0 skipped** (51 API + 1 runner), PostgreSQL 16.10 Testcontainers throughout. No H2.

Every database rejection asserts its own guard's message. All guards in V3 and V4 raise SQLSTATE 23514, so asserting only the exception type cannot tell which constraint fired — or whether the intended one fired at all. Adding reason assertions immediately falsified one of this slice's own assumptions: a second attempt against a `QUEUED` run is rejected by the per-row guard, not by the uniqueness constraint the test claimed to exercise.

New `SchedulingHttpIntegrationTests` (9 tests):

- **success** — `QUEUED`, `runVersion` exactly 2, ETag `"run-2"`, queue timestamps set and consistent with the configured timeout, attempt #1 `WAITING_FOR_CLAIM` with the exact expected column set (proving no assignment), dispatch bound to the snapshot digest, one outbox row unpublished, canonical sixteen-field payload with no claim-time authority, digest recomputed from the parsed message
- **atomicity** — a failure after the durable writes leaves the run `CREATED`, `runVersion` 1, no queue timing, no attempt, no dispatch, no lifecycle event, no outbox row; the run remains schedulable afterwards
- **concurrency** — ten barrier-synchronized schedulers against one run: exactly one `SCHEDULED`, nine `ALREADY_SCHEDULED`, `runVersion` 2, one of each durable record
- **independence** — six distinct runs scheduled concurrently all succeed with independent bundles, proving no guard queries across runs
- **repeat** — scheduling an already-`QUEUED` run at both the stale and current version is a no-op
- **tenancy** — stale version rejected; foreign organization and unknown run both conceal as 404; a cross-tenant attempt forged directly against the database is rejected by composite ownership
- **database** — every unsupported `test_runs` mutation and delete rejected before and after scheduling; attempt, dispatch, lifecycle event, and outbox `UPDATE`/`DELETE` rejected; `TRUNCATE` rejected on all six evidence tables; snapshot still immutable; an attempt for a non-`QUEUED` run rejected; the V1→V4 chain asserted applied
- **deferred bundle** — a hand-written partial bundle (legal transition plus attempt, no dispatch/event/outbox) is accepted statement by statement and rejected at **commit** by the deferred constraint triggers, and the whole partial bundle rolls back together. Without this the five constraint triggers could be deleted with the suite still green
- **payload guard** — the four demonstrated exploits: a dropped key replaced by an attacker-chosen one at the same cardinality, JSON null in five different fields, a string where the contract requires a number, and a wrong non-null tenant

New `RunLifecycleTest` (5 tests) is the state-machine oracle: `CREATED → QUEUED` valid, `CREATED → RUNNING` and `CREATED → CLAIMED` invalid, `QUEUED → CLAIMED` defined but unreachable (asserted by reflection over `TestRun`'s mutators), version increment and field carry-through, queue-timing coherence, and the attempt's refusal of a non-initial number or any assigned state.

`ExecutionDispatchPolicyTest` gained a whole-second digest vector — the case where an unnormalized `toString()` would diverge — and that vector was **independently reproduced from the published rules in a second language** before the Java assertion was written. `RunSchedulingConfigurationTest` covers the queue-timeout bounds. `ControlPlaneArchitectureTest` gained a canary, because every rule there uses `allowEmptyShould(true)` and a mistyped package selector would otherwise pass silently.

The concurrency test asserts that at least two scheduling calls genuinely overlapped in wall-clock time: one winner and nine losers is also what ten *sequential* calls produce, so the disposition counts alone do not prove a race happened. The Hikari pool is sized above the scheduler count, since each blocked scheduler parks a connection and the default pool of ten left no headroom.

Dependency boundary confirmed by inspecting the resolved `runtimeClasspath` (274 entries): no AMQP or RabbitMQ client, no Karate, no MinIO, no docker-java, no Testcontainers, no secret-provider SDK. The runner still cannot execute tests.

## 19. Files changed

**Corrected (inherited, defective):**
- `apps/api/src/main/resources/db/migration/V4__run_scheduling_dispatch_outbox.sql` — `jsonb_object_length` → `jsonb_object_keys`; fail-closed payload guard with exact key set, JSON type checks, and explicit-offset timestamps; `cancellation_status` required `NOT_REQUESTED`; bundle invariant decoupled from delivery state and bound to the outbox timestamp; `TRUNCATE` guards
- `packages/api-contracts/scripts/validate-schemas.mjs` — moved the dispatch assertions into scope; added and widened the claim-time authority boundary check
- `apps/api/src/main/java/com/kaas/api/controlplane/application/RunSchedulingService.java` — removed an unused constant; private dispatch mapper; clock clamp; guarded `afterCommit`; version-aware `ALREADY_SCHEDULED`; cancellation check; pinned isolation; narrowed catch
- `apps/api/src/main/java/com/kaas/api/controlplane/domain/ExecutionDispatchPolicy.java` — fixed-precision timestamp canonicalization
- `apps/api/src/main/java/com/kaas/api/controlplane/domain/ExecutionDispatch.java` — compact constructor invariants
- `apps/api/src/main/java/com/kaas/api/controlplane/infrastructure/JdbcRunSchedulingRepository.java` — digest prefix assertion
- `apps/api/src/main/java/com/kaas/api/shared/ApiExceptionHandler.java` — database exceptions no longer log SQL or server text
- `apps/api/src/test/java/com/kaas/api/ControlPlaneArchitectureTest.java` — selector canary
- `docs/api/openapi-v1.yaml` — added `queueStartedAt`/`queueDeadlineAt` to `Run`; documented `"run-1"`/`"run-2"` ETags and that scheduling has no production trigger yet
- `packages/api-contracts/README.md` — published the canonical digest rules and a frozen test vector

**Added:**
- `apps/api/src/test/java/com/kaas/api/SchedulingHttpIntegrationTests.java`
- `apps/api/src/test/java/com/kaas/api/controlplane/domain/RunLifecycleTest.java`
- `apps/api/src/test/java/com/kaas/api/controlplane/application/RunSchedulingConfigurationTest.java`
- `docs/adr/017-transactional-scheduling-and-outbox.md`
- `docs/architecture/scheduling-outbox-slice.md`
- `SCHEDULING_OUTBOX_SLICE_REPORT.md`
- `README.md`, `IMPLEMENTATION_STATUS.md`, `docs/adr/README.md` updates

**Preserved unchanged (inherited, sound):** the scheduling domain model, `RunSchedulingService` logic, `RunSchedulingRepository`, `JdbcRunSchedulingRepository`, the remainder of V4, `execution-dispatch.schema.json` and its nine fixtures, the `runner-command.schema.json` correction, `ExecutionDispatchPolicyTest`, the ArchUnit guard, the Testcontainers dependency ban, and the `application.properties` scheduling configuration — plus the entire uncommitted TestRun intent slice.

## 20. Verification

| Gate | Result |
|---|---|
| `./gradlew clean check` | PASS — 52 tests, 0 failures/errors/skips |
| `npm --prefix packages/api-contracts ci && test` | PASS — 5 schemas, all fixtures, semantic invariants |
| `npm --prefix packages/api-contracts run lint:openapi` | PASS — zero warnings |
| `npm --prefix apps/web ci / lint / typecheck / test / build` | PASS |
| `npm --prefix apps/web audit --omit=dev` | PASS — 0 vulnerabilities |
| `docker compose -f infrastructure/local/docker-compose.yml config` | PASS |
| `git diff --check` | PASS — no whitespace errors |
| Runtime dependency graph | PASS — no broker, Karate, object-store, container, or secret-provider client |

Nothing was environment-blocked. Docker was available, so all Testcontainers tests genuinely executed against PostgreSQL 16.10.

## 21. Residual risks

- **The outbox has no relay.** Messages accumulate unpublished. Correct for this slice, but it must be paired with a publisher in the next one or runs will sit in `QUEUED` forever.
- **No queue-deadline enforcement.** `queueDeadlineAt` is recorded but nothing acts on expiry, because reaping a timed-out run is itself a lifecycle transition this slice does not implement.
- **The database state machine is large.** It is deliberate and readable, but each future transition must extend the V4 guard rather than relax it, and the guard should be revisited as a unit when claim lands.
- **Single-attempt schema.** `uq_execution_attempts_one_per_run` and the `attempt_number = 1` checks must be dropped together for infrastructure retry — intended friction, but a migration.
- **`assignmentEpoch` is absent, not null.** Adding claim-time state will require new columns and a new message type rather than filling in blanks.
- **The uncommitted tree now spans two slices.** TestRun intent and scheduling/outbox are both uncommitted; they should be committed as two separate commits to preserve reviewable history.
- **Scheduling has no production trigger.** Implemented and tested, but nothing calls it outside tests, so a deployed run stays `CREATED`. Deliberate — a trigger without a publisher queues runs nothing can dequeue — and now stated in the OpenAPI description rather than left implied.
- **Flyway checksum drift.** V4 changed after the earlier version had been applied to scratch databases. V4 is uncommitted so this is expected churn, but any environment that already ran the earlier V4 needs a `flyway repair` or a reset.
- **The outbox is a dispatch delivery table, not a general outbox.** `dispatch_id` is `NOT NULL` with a composite FK and the type/version columns are pinned by check constraints, so a second message type cannot be written without a migration. `messaging-reliability.md` describes lifecycle events flowing through an outbox; the `RUN_QUEUED` event here does not, and structurally cannot. Resolve this when the relay lands, either by generalizing the table or renaming it.
- **Stored payload versus published schema.** The contract fixtures are hand-written rather than generated from `ExecutionDispatchPolicy`, so schema-versus-implementation drift is caught only by the exact key-set assertion in the integration test, not by the contract suite.
- **`ck_execution_dispatches_snapshot_identity` welds snapshot identity to run identity.** True today, and mirrored in a published schema description. If snapshots ever become revisable or shareable, changing it is a breaking contract change rather than an additive one.
- **`INVALID_STATE` is unreachable and untested.** It requires a cancellation status this slice cannot set. It becomes testable when cancellation lands.

## 22. Recommended next slice

**Outbox relay and broker publication**, in that order and alone: a claim-based relay that reads unpublished rows in `occurred_at, message_id` order, publishes to RabbitMQ, and records `published_at`, `publish_attempts`, and failure classification — with the delivery-metadata check constraint relaxed to permit exactly that update and nothing else. It needs no worker, no claim, no capability, and no execution, so it keeps the hostile-execution gate closed while making the queue real. It also brings the production trigger that scheduling currently lacks.

That slice must add the delivery columns this one deliberately did not invent, because they have no use until a relay exists: `available_at` (with the pending index moved to `(available_at, message_id) WHERE published_at IS NULL`), `last_attempt_at`, and a terminal/dead-letter disposition. Without them a permanently unroutable message is re-selected at full speed on every tick and can never be dead-lettered — and holding that state in relay memory fails the moment there are two relay instances, which is the same argument that rejected application locking for scheduling. Trace context belongs there too.

Two V4 guards must be **rewritten, not extended**, when claim lands: `guard_initial_execution_attempt` requires the run's `queued_at` to equal the attempt's `created_at`, which attempt #2 can never satisfy; and `require_complete_scheduling_bundle`'s `ELSIF` branch rejects every transition *out* of `QUEUED`, because the attempt row still exists.

**Worker claim, assignment epoch, and lease** should follow as a separate slice, and only then may `ExecutionCommand` production begin.
