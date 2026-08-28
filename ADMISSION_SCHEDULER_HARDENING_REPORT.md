# Tenant Admission and Durable Scheduler Hardening Slice Report

## 1. Executive summary

Three things this slice closes, all of them residual risks the relay slice named and deliberately left open.

An organization now has a ceiling on concurrent work, enforced under a per-organization advisory lock so concurrent requests cannot overshoot it. The scheduler's retry delay moved out of a process-local map and into PostgreSQL, so a restart no longer immediately retries a run that just failed. And every migration is now verified against a populated previous-version database, not only an empty one.

No consumer, no worker claim, no assignment epoch, no execution. RabbitMQ publication is unchanged.

## 2. Threat and amplification rationale

Run creation used to be inert — a row and nothing else. Since the relay slice, one `POST /runs` automatically becomes a queued run, an execution attempt, a dispatch intent, a durable outbox row that is never deleted, and a broker message. Creation was authenticated but otherwise unbounded, so a single tenant could convert request volume into storage and broker load for the whole platform.

The scheduler compounded it. Its poison-pill cooldown lived in memory, so a restart retried a failing run immediately and two replicas never agreed on whether a run was being held back.

## 3. Admission model

| Ceiling | Gates | Unit cost |
|---|---|---|
| `kaas.admission.max-active-runs-per-organization` | `POST /runs` | one run row plus its sealed snapshot |
| `kaas.admission.max-queued-runs-per-organization` | `CREATED → QUEUED` | attempt + dispatch + permanent outbox row + broker message |

**Active** means every state that is not complete. Defining it as the two states that exist today would have made the ceiling silently stop binding the moment `QUEUED → CLAIMED` lands, and the index predicate would then need another migration. Query, index, and tests all say "not complete".

The split is the point. Holding intent is nearly free, so a burst is accepted and waits; converting intent into queued work is expensive, so that is where it is throttled. One combined ceiling would refuse cheap requests at the API for an expense not yet incurred.

Both limits are server configuration, validated at startup (positive, bounded, and queued ≤ active so the queue ceiling is actually reachable). No request body, header, or token claim can influence them.

Deliberately **not** built: rate limiting, token buckets, Redis, billing, plans, or a generic quota framework.

## 4. Concurrency model

```
BEGIN
  advisory lock (per-key idempotency)      -- pre-existing
  resolve replay -> return early if present
  advisory lock (per organization)         -- new, only on the new-admission path
  count runs that are not complete
  over capacity -> 429, nothing written
  otherwise     -> TestRun + sealed RunSnapshot + idempotency record
COMMIT
```

Without serialization this is theatre: twenty simultaneous requests each observe the same pre-insert count, all pass, and the organization overshoots by nineteen. The lock is what turns the count into a decision.

Lock ordering is identical on every path — idempotency first, organization second — so the two cannot deadlock. The two locks use PostgreSQL's two-argument advisory form with distinct lock classes, so they occupy genuinely disjoint key spaces: a string namespace inside one shared space only permutes bits, and a collision there could have inverted the ordering and deadlocked.

The lock is taken immediately before the count and the insert, not at the top of the transaction. Everything above it — revision lookups, snapshot materialisation — is read-only or pure computation, and holding the lock across it would let one busy organization park every pooled connection and stall reads for every other tenant.

No counter table: there is nothing to keep consistent, and `ix_test_runs_admission` (partial, on `organization_id, lifecycle_state`, restricted to non-complete runs) answers both counts with an **Index Only Scan** whose cost tracks active runs rather than total history. Verified with `EXPLAIN`, including against 800k completed runs.

## 5. Idempotency interaction

Replay is resolved **before** admission. An already-successful replay returns its original run even at capacity: it creates nothing, and refusing it would punish a client for retrying safely — precisely what idempotency exists to make free.

A new key is new work and obeys the current ceiling. Both halves are tested, including the case where a client at capacity replays an old key successfully and is then refused a new one in the same test.

## 6. Queue admission

At the queue ceiling a run stays `CREATED` with a durable next-attempt time. It is **not** a failure: it accrues no failure count and can never be quarantined for waiting, and the database enforces that — a control row may carry a quarantine only once it has at least one real failure.

The run keeps `runVersion` 1 and gains no attempt, dispatch, or outbox row. Once capacity frees, it schedules normally and its control row is deleted.

## 7. Durable scheduler backoff

`run_scheduling_control` holds `failure_count`, `next_attempt_at`, `last_attempt_at`, `last_failure_code`, and `quarantined_at`, keyed by run and bound by the same composite ownership as everything else.

Selection is a left join: eligible when there is no control row at all, or when the row is neither quarantined nor still serving its delay. It also skips organizations already at their queue ceiling and round-robins across the rest, so one tenant's backlog cannot occupy the batch window and starve everyone else.

It is a **separate table rather than columns on `test_runs`** deliberately. Scheduling is technical state; putting it on the aggregate would mean permitting a second class of mutation and weakening the guard that makes `CREATED → QUEUED` the only permitted transition — trading a real lifecycle invariant for bookkeeping convenience. A trigger additionally restricts control state to runs still awaiting scheduling, so a stale writer cannot resurrect eligibility for something already queued.

Success **deletes** the row. Absence is the single representation of "nothing is holding this run back", so no stale eligibility can outlive the transition it was gating.

## 8. Failure classification

| Condition | Counted | Behaviour |
|---|---|---|
| queue at ceiling | no | deferred, never quarantined; may postpone an existing delay but never shorten it |
| transient (database timeout, internal error) | yes | escalating bounded delay, capped |
| trusted input that cannot be valid | yes | quarantined immediately — retrying is guaranteed waste |
| budget spent | — | quarantined |

In every case the run stays `CREATED` with no test outcome and no infrastructure outcome. Infrastructure being briefly unhealthy is not a verdict on a test run, so this slice invents no terminal lifecycle transition and never touches lifecycle or version.

**Operator recovery:** delete the run's row from `run_scheduling_control`; it becomes immediately eligible and nothing about the run needed repairing. There is deliberately no endpoint — reviving a poisoned run is a decision, and the failure modes are still being learned. This is covered by a test that quarantines a run, proves it is withheld even after its delay elapses, then clears it and watches it schedule.

## 9. Persistence

Flyway **V6**. V1–V5 are untouched historical migrations.

It adds `ix_test_runs_admission`, the `run_scheduling_control` table with a quarantine index, and a guard trigger that locks the run it checks. It transforms **no existing rows**, so no backfill is required and none was invented — the populated-upgrade test asserts the new table starts empty rather than back-filled.

## 10. Migration-upgrade strategy

A fresh-schema migration test is not a migration test. A backfill over zero rows cannot trip a guard, violate a constraint, or leave a NULL behind, so the empty-database run is silent about exactly the failures that matter. That is how the relay slice shipped a migration rejected by its predecessor's own trigger.

Every migration now runs in both directions:

| Direction | Proves |
|---|---|
| empty → current | the chain applies, nothing pending |
| previous version + representative rows → current | a migration can transform rows the previous version's triggers were protecting |

The baseline is derived from the files on disk (second-to-last version), so the gate needs no editing when a migration is added. The fixture seeds a project, feature/environment/profile revisions, a `CREATED` run, a `QUEUED` run with its complete scheduling bundle, and outbox rows in all four delivery states — pending, published, retrying, and terminally failed. Seeding uses `session_replication_role = replica` on a **single connection** (it is a session setting; a connection per statement silently re-enables the guards midway), then restores it so the migration under test runs with every guard installed.

## 11. OpenAPI and error behaviour

`POST /runs` documents `429` with a stable `RUN_QUOTA_EXCEEDED` code. The description states the limit is server-controlled and that a replay is resolved before admission.

It does **not** reuse the shared `RateLimited` response, because that documents a `Retry-After` this endpoint deliberately does not send: capacity frees when the organization's own runs complete, and no honest duration exists. The body discloses no counts, no capacity, no other tenants, and no SQL — asserted directly in a test.

No public endpoint exposes quota state, scheduler backoff, or quarantine records. Those are operational internals.

## 12. Observability

`kaas.run.admission.rejected`, `kaas.scheduler.deferred`, `kaas.scheduler.failures`, and the `kaas.scheduler.quarantined` gauge. Dimensioned by **reason category only**. A test asserts that no `kaas.*` meter carries an `organizationId`, `projectId`, `runId`, `principalId`, or `messageId` label.

Logs carry trusted resource identifiers and bounded reason codes: `RUN_ADMISSION_REJECTED`, `RUN_SCHEDULING_DEFERRED`, `RUN_SCHEDULING_FAILED`, `RUN_SCHEDULING_QUARANTINED`, `RUN_SCHEDULING_RECOVERED`. Never request bodies or configuration.

## 13. Security review

| Threat | Outcome |
|---|---|
| Quota race bypass | Advisory lock + count + insert in one transaction; twenty concurrent creates admit exactly one |
| Idempotency bypass of quota | Replay creates nothing; a new key obeys the ceiling. Both tested |
| Cross-tenant interference | Lock and counts are keyed per organization; a saturated tenant does not affect another, tested |
| User-controlled policy | Limits are configuration only; a body field is rejected by the closed request schema and a header is ignored |
| Error leakage | 429 body carries no counts, capacity, SQL, or tenant identity; no fabricated `Retry-After` |
| Unbounded COUNT | Partial index, index-only scan, cost tracks active runs not history |
| Scheduler hot loop | Durable delay; a restart cannot erase it; quarantine bounds a poison run |
| Scheduler restart amplification | Verified with a freshly constructed scheduler that has no process memory |
| Queue amplification | Queued ceiling defers rather than producing dispatches and broker messages |
| Metric cardinality | Reason labels only, asserted across every `kaas.*` meter |
| Redis introduced for rate limiting | Not introduced |

## 14. Test evidence

`./gradlew clean check` — **103 tests, 0 failures** (102 API + 1 runner).

New `AdmissionAndSchedulerHardeningTests` (15): capacity boundary and safe 429 shape; replay succeeding at capacity while a new key is refused; tenant independence; twenty concurrent creates admitting exactly one; request unable to supply its own capacity (body rejected, header ignored); queue ceiling holding a run at `CREATED` with no attempt, dispatch, or outbox and no failure counted; deferred run scheduling once capacity frees and its control row cleared; concurrent replicas not overshooting the queue ceiling; a failure persisting its delay and surviving a freshly constructed scheduler; escalating delay and quarantine after the budget, with the run still `CREATED` and outcome-free, withheld even after the delay elapses, then recovered by an operator; immediate quarantine for impossible input; the guard refusing control state for an already-scheduled run; metric labels carrying no tenant identity.

New `MigrationUpgradeTests` (2): fresh and populated-upgrade directions.

Every test mints a fresh organization, so per-organization counts are naturally isolated despite the shared context. Assertions on `scheduleDue()` are scoped per tenant rather than to its global return value, because the scheduler is global and other tests' runs would otherwise perturb it.

### What independent review changed

Five specialist reviews (PostgreSQL concurrency, API/security, scheduler/distributed systems, migration engineering, quality engineering) read the code, the migration, and the tests. Two ran the migration chain and the concurrency scenarios against live PostgreSQL. Every substantive finding is fixed.

| Severity | Finding | Fix |
|---|---|---|
| P0 | **The migration gate's fixture reproduced the exact blind spot it was built to close.** It seeded outbox rows with `dispatch_id = NULL` and the one message type production can never hold, so V5's *joined* backfill would have matched zero rows and passed green. The probe used to verify the gate was an unconditional `UPDATE` — too strong to detect this | Fixture rebuilt as four queued runs each with a complete bundle and one dispatch-backed outbox row per delivery state; re-verified with a **joined, filtered** probe in V5's actual shape |
| P0 | One tenant inside its own quota could starve every other tenant: selection was globally FIFO, so a saturated organization's deferred runs occupied every batch forever | Selection skips saturated organizations and round-robins across the rest |
| P0 | Test isolation: `scheduleDue()` is global, so each test's leftover runs were scheduled — and, when a test stubbed the scheduler to throw, *poisoned* — by the next. One test was passing vacuously as a result | Per-test cleanup of the run tables |
| P1 | `record()` called the database *before* it could record a failure, so a partial outage wrote no backoff and left the run instantly eligible — a hot loop, and a regression against the in-memory version it replaced | Timestamps, delay, and quarantine all derived in the single recording statement |
| P1 | The organization lock was held across revision lookups, snapshot materialisation, and up to a thousand row inserts, so one busy tenant could exhaust the connection pool and stall reads for everyone | Critical section shrunk to the count plus the write; pool size pinned |
| P1 | The scheduler's queue ceiling was an unlocked read-then-act, so replicas could each observe the same count and overshoot | Ceiling, count, and transition now share one transaction and the same organization lock |
| P1 | `SchedulingBackoff.delayAfter` and `shouldQuarantine` became **dead code once the curve moved into SQL**, and a unit test was certifying a formula the system never executes | Both removed; the statement returns the authoritative count and quarantine flag, and the delay magnitude is asserted against real SQL |
| P1 | "Active" was defined as `CREATED + QUEUED` in both the query and the index, so the ceiling would silently stop binding the moment `QUEUED → CLAIMED` landed | Defined as "not complete" in query, index, and tests, with a test proving a QUEUED run still occupies capacity |
| P2 | The V6 guard read `test_runs` without a lock, so a concurrent transition slipped past it and could leave an unrepairable control row | `FOR KEY SHARE` |
| P2 | Scheduler backoff had no jitter, unlike the relay, so an outage-wide failure re-converged on one instant | Bounded jitter added and pinned in tests |
| P2 | The quarantine gauge queried the database on every metrics scrape | Cached, refreshed by the scheduler's own pass |
| P2 | Capacity-per-project was indistinguishable from capacity-per-organization; a deferral could shorten an accumulated backoff; "repeating the pass changes nothing" passed because of the cooldown, not the ceiling | Test added for a second project in the same organization; deferral now only postpones; the repeat assertion makes the run due first |
| P3 | Both advisory locks shared one 64-bit key space, so a collision could invert the lock ordering and deadlock | Two-argument form with distinct lock classes |
| P3 | An unused index on `run_scheduling_control`; a per-run failure could abort a whole batch; `restartedScheduler()` shared every collaborator and proved nothing | Index dropped; per-run isolation added; the helper removed in favour of asserting durable eligibility directly |

## 15. Migration evidence

V1→V6 applies to a fresh database and to a populated V5 database carrying representative rows.

**The gate was verified to be real, and the first verification was not good enough.** The initial probe was an *unconditional* `UPDATE outbox_messages`, which touches every row regardless of predicate — it proved triggers were enabled, but not that the fixture holds rows a *filtered or joined* backfill would select. Migration review caught that the fixture seeded only `dispatch_id = NULL` rows, so V5's actual backfill would have matched nothing and passed.

The fixture was rebuilt to hold four dispatch-backed `EXECUTION_DISPATCH` rows, and the gate was re-verified with a probe in V5's real shape:

```sql
UPDATE outbox_messages o SET last_failure_code = 'PROBE'
  FROM execution_dispatches d
 WHERE d.dispatch_id = o.dispatch_id AND o.message_type = 'EXECUTION_DISPATCH';
```

The **populated** test failed with the trigger's `SQLSTATE 23514`; the **fresh** test still passed. The probe was removed. That is the exact signature of the defect this gate exists to catch, and it is now caught for the right reason.

`EXPLAIN` confirms the admission count is an `Index Only Scan using ix_test_runs_admission`, not a sequential scan.

## 16. Files changed

**Added** — `V6__admission_and_scheduling_control.sql`; `AdmissionPolicy`, `SchedulingBackoff`, `SchedulingFailure`; `AdmissionRepository`, `SchedulingControlRepository`; `JdbcAdmissionRepository`, `JdbcSchedulingControlRepository`; `AdmissionAndSchedulerHardeningTests`; `MigrationUpgradeTests`; ADR-019; `docs/architecture/admission-scheduler-hardening.md`; this report.

**Modified** — `RunIntentService` (admission check and rejection metric); `PendingRunScheduler` (rewritten: durable backoff, queue ceiling, failure classification, in-memory cooldown removed); `SchedulableRun` (+`projectId`); `JdbcRunSchedulingRepository` (eligibility join); `ApiException` (429 factory); `application.properties`; `openapi-v1.yaml`; `SchedulingHttpIntegrationTests` (removed a hand-maintained migration-version list now covered properly); `README.md`; `IMPLEMENTATION_STATUS.md`; `docs/adr/README.md`.

## 17. Verification

| Gate | Result |
|---|---|
| `./gradlew clean check` | **PASS — 103 tests, 0 failures/errors/skips** |
| PostgreSQL + RabbitMQ Testcontainers | PASS |
| Fresh migration V1→V6 | PASS |
| Populated upgrade V5 + rows → V6 | PASS |
| `npm --prefix packages/api-contracts test` / `lint:openapi` | PASS |
| web lint / typecheck / test / build / audit | PASS, 0 vulnerabilities |
| `docker compose … config` | PASS |
| `git diff --check` | PASS |
| Runtime dependencies | RabbitMQ present as intended; no Karate, MinIO, docker-java, or secret-provider SDK |

## 18. Residual risks

- **`CREATED` runs still accumulate.** A tenant at its queue ceiling keeps its intent, and nothing expires it. Cheap, but not free, and there is no retention policy for runs, outbox rows, or quarantine records.
- **Admission serializes creation per organization** for the duration of the transaction, which includes snapshot materialization. That is a deliberate per-tenant throughput ceiling, not a platform-wide one, but it is a real cost of a decisive count.
- **Head-of-line ordering across tenants.** `findSchedulable` orders globally by `created_at`. A tenant with many eligible runs occupies the batch window; deferral pushes theirs out, which mitigates it, but there is no per-tenant fairness in the batch itself.
- **Quarantine recovery is manual SQL.** No endpoint and no operator tooling.
- **The migration fixture must grow.** A future migration touching a table the fixture does not seed would be tested against absent data — the exact failure the fixture itself had before review. Eleven tables are still unseeded, including `run_snapshot_features`, which carries the same guard-over-existing-rows shape that produced the original defect. The fixture is a living artifact, not a one-off, and a coverage forcing function is the right next step.
- **The upgrade baseline is the previous version only.** If two migrations ship together the earlier one is tested only against an empty database, and if a version was never deployed the real upgrade path skips a step the gate never exercised. Per-version fixtures keyed to the oldest deployed version would close this.
- **The gate cannot detect an edited historical migration.** Checksums are computed from the same files that were just applied, so an edited V4 yields a self-consistent history and a green test — while every deployed environment would refuse to start. A checked-in checksum manifest would close it.
- **Post-migration assertions are structural, not content-level.** A migration that silently rewrote payloads or swapped identifiers would still pass. Per-table content digests captured before and after would catch it.
- **`CREATE INDEX` is not concurrent.** V6 builds its index in a transaction, which blocks writes to `test_runs` for the duration. Harmless at current volume, a real outage at scale.
- **Quarantined runs are never reclaimed.** They stay `CREATED` forever and are re-scanned every tick. Bounded per organization by the active ceiling, but unbounded across many organizations; a reaper or a terminal disposition is needed before this runs long.
- **Limits are global, not per-tenant.** Every organization gets the same ceiling; there is no per-tenant override, which is correct while there is no plan model but will not survive real customers.

## 19. Required claim-slice changes

Unchanged and deliberately not weakened. These must be rewritten **together**, as one migration:

- `require_complete_scheduling_bundle` — its `ELSIF` branch rejects any transition *out* of `QUEUED`, because the attempt row still exists.
- `guard_initial_execution_attempt` — requires the run's `queued_at` to equal the attempt's `created_at`, which attempt #2 can never satisfy.
- `ck_run_lifecycle_events_schedule` — pins `sequence = 1` and `CREATED → QUEUED`.
- `uq_execution_attempts_one_per_run`, `ck_execution_attempts_initial_number`, `ck_execution_attempts_initial_state`, `ck_execution_dispatches_attempt_number`.

## 20. Recommended next slice

**Consumer inbox**, alone. A consumer that deduplicates on `messageId`, treats a repeated identity with a differing digest as an integrity conflict rather than a redelivery, and records processing in the same transaction as its effects. It needs no capability and no execution authority, so it keeps the hostile-execution gate closed while making the duplicate window the relay slice created provably safe — which is the precondition for anything that acts on a dispatch.

**Worker claim** follows separately: assignment epoch, lease, fencing, and the guard rewrite in §19, and only then `ExecutionCommand` production. It should not be combined with the inbox, and neither should be combined with runner execution — KAA-004, the hostile-execution boundary, remains the gate before anything actually runs a test.
