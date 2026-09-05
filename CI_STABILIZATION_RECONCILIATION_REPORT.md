# CI stabilization and documentation reconciliation

## 1. Executive summary

Five backend tests failed in GitHub Actions and passed on every local run. They were not flaky, and they were
not wrong. They were correct tests detecting a real defect that the developer machine could not express.

Every server-generated instant in this system is taken from a `Clock`, returned to the caller, and written to a
PostgreSQL `timestamptz`. PostgreSQL stores microseconds; a Java `Instant` carries nanoseconds. So the value
returned at creation was not the value durably committed, and any later read of the same unchanged resource —
an idempotent replay, a GET — returned a different representation.

**`Clock.systemUTC()` returns microseconds on macOS and nanoseconds on Linux.** On the developer machine there
was nothing to lose, so the defect could not appear; in CI it appeared on every affected path. A correctness
property had become a property of where the code happened to run.

The fix is one line in one place: the application clock is wrapped at microsecond resolution, so the
application never holds an instant the database cannot store. No assertion was weakened, no test disabled, no
sleep or retry added, no environment branch introduced.

Verdict: **READY FOR KAAS-13** — subject to §20, which records the GitHub Actions result.

## 2. Starting Git SHA

`afa82bb84afc8183d2e6c935bad48c994f90f34d` on `codex/project-feature-control-plane`.

## 3. Original GitHub Actions run and failure

Run `33930449921`, head `afa82bb`, created 2026-09-04T23:41:40Z, conclusion **failure**.

| Job | Conclusion |
|---|---|
| backend | **failure** |
| hostile-execution-gate | success |
| synthetic-execution-pipeline | success |
| web | success |
| contracts | success |
| infrastructure | success |

`269 tests completed, 5 failed`. CI Java: **Temurin 25.0.4+1**. Gradle 9.7.1 (pinned wrapper).

## 4. Exact five failing tests

1. `ConfigurationHttpIntegrationTests.runIntentCreatesOnlyASealedReproducibleSnapshotWithSafeTenantScopedReads`
2. `ConfigurationHttpIntegrationTests.secretReferencesAndEnvironmentsAreSafeImmutableCanonicalAndTenantScoped`
3. `ConfigurationHttpIntegrationTests.everyNewPostUsesConcurrentTransactionalIdempotencyAndPrincipalScope`
4. `ControlPlaneHttpIntegrationTests.featureCreationIsAtomicAndRevisionsAreExactImmutableAndTenantScoped`
5. `EarlyTerminalLifecycleTests.cancellationIsIdempotentByStateAndWritesNothingTheSecondTime`

All five compare an initial response body to a replay or read-back body for exact equality.

## 5. Reproduction evidence

The hypothesis was tested, not assumed.

**Host precision probe.** On this machine, `Clock.systemUTC().instant()` returns `nano % 1000 == 0` on every
reading — microsecond precision. Linux returns nanoseconds. That alone explains local green and CI red, but it
does not prove causation.

**Deterministic local reproduction.** The clock bean was temporarily wrapped to add 789 nanoseconds to every
reading, emulating a Linux clock. The result was **exactly the same five tests, no more and no fewer**.

**Exact delta.** Parsing both JSON bodies from failure 4 and diffing field by field yields precisely one
differing field:

```
createdAt  expected '2026-09-05T00:13:21.057577789Z'   (in-memory Instant, nanoseconds)
createdAt  actual   '2026-09-05T00:13:21.057578Z'      (read back from PostgreSQL, microseconds)
```

Nothing else differed: not ordering, not nullability, not `runVersion`, not audit fields, not ETags, not
idempotency metadata, not serialization.

## 6. Root cause

`ApplicationConfiguration.clock()` returned a raw `Clock.systemUTC()`. Creation paths take `clock.instant()`,
persist it via `Timestamp.from(...)`, and construct the response from the in-memory value — `saveAndFlush`
returns the entity as held in the persistence context, which still carries nanoseconds. Any later read
reconstructs the resource from the column, which holds microseconds.

**One detail matters more than it looks.** PostgreSQL **rounds** to microseconds; `Instant.truncatedTo`
**truncates**. `.057577789` is stored by PostgreSQL as `.057578` and truncated by Java to `.057577`. Normalising
at the persistence boundary would therefore have produced a representation off by one microsecond from what was
actually stored — a fix wearing the shape of a fix. Normalising at the source removes the question: the
application never holds a value that needs rounding, so the two systems have nothing to disagree about.

## 7. Why the bug appeared in CI

Not a JVM patch-level difference, and not GitHub-specific. `Clock.systemUTC()` derives its resolution from the
underlying OS clock: microseconds on macOS, nanoseconds on Linux. The defect was always present in the code and
was unobservable on the machine used to verify it.

## 8. Production code changes

Three files, fourteen lines.

- **`apps/api/src/main/java/com/kaas/api/shared/PersistableClock.java`** (new) — wraps a clock at
  `Clock.tick(source, Duration.ofNanos(1_000))`, with the rounding-versus-truncation reasoning recorded where
  the next reader will need it.
- **`apps/api/src/main/java/com/kaas/api/ApplicationConfiguration.java`** — the `Clock` bean is now
  `PersistableClock.wrapping(Clock.systemUTC())`.
- **`JacksonResultDocumentReader` and `CommandValidator`** — `isTextual()` → `isString()`, clearing the one
  deprecation warning (§11). Identical semantics; no behavioural change.

No `truncatedTo` was scattered anywhere. No repository, controller, service, or response shape changed.

## 9. Idempotency invariant after the fix

Unchanged in contract and now actually honoured:

- same principal, operation, key, and semantic request → same resource, same canonical representation, same
  `Location`, same replay indication;
- same key, different semantic request → `409 IDEMPOTENCY_CONFLICT`;
- concurrent first use → exactly one durable resource, all callers observing the same representation.

PostgreSQL remains authoritative. **No JVM-global lock, no static monitor, no process-local idempotency state**
was introduced — the fix is a clock, not a serialization point, and adds no coordination whatsoever.

## 10. Timestamp and persistence semantics

Every server-generated instant is now storable exactly, so the value returned at creation *is* the value
committed. There are 14 `clock.instant()` call sites in the control plane and all are covered by construction —
the guarantee is a property of the injected bean, not of discipline at each site.

Audited for other precision assumptions:

- **Nothing bypasses the clock** in control-plane production code — no direct `Instant.now()`.
- The runner's `Instant.now()` uses in `DockerSandboxLauncher` measure elapsed durations and are never
  persisted.
- `HostileExecutionSecurityGate` stamps an attestation's `assessedAt`, which reaches a document and its digest
  but **never a PostgreSQL column** — inspected and confirmed to be a different class, not the same defect.
- `OrphanSandboxReconciler` compares against Docker's epoch-**second** container timestamps.

## 11. Cancellation semantics validation

`cancellationIsIdempotentByStateAndWritesNothingTheSecondTime` passes unmodified. The second cancellation
returns the same canonical terminal representation, does not increment `runVersion`, adds no lifecycle event,
and duplicates no durable side effect. First-CAS-wins lifecycle semantics are untouched — the only thing that
changed is that the first response now reports the instant that was actually committed.

## 12. Regression tests added

Every one is host-independent by construction, because the original defect was invisible on one host.

- **`PersistableClockTest`** — proves the property against a *fixed nanosecond-bearing* source rather than the
  host clock, across boundary values `.000000001`, `.999999999`, an already-microsecond value, and zero. Also
  asserts the produced instant is never later than the source (several schema guards bound an application
  instant against the database clock) and never more than one microsecond earlier. Carries an anti-vacuity test
  that the source genuinely has sub-microsecond digits.
- **`ApplicationClockTest`** — asserts the *wiring*, comparing the configured bean to
  `PersistableClock.wrapping(Clock.systemUTC())` via `Clock.tick` equality. Asserting that the clock merely
  *produces* microseconds would pass on macOS with the fix removed, which is exactly how the defect survived.
  Includes an anti-vacuity assertion that a raw system clock does not satisfy the comparison.
- **`NanosecondSourceClockConfiguration`** — a shared test configuration imported by all three affected suites,
  wiring the production wrapper over a deliberately nanosecond source, so the original five assertions now
  exercise a nanosecond host everywhere.

**Mutation verification:**

| Mutation | Result |
|---|---|
| Unwrap the clock bean | RED — `ApplicationClockTest` |
| Make the wrapper a no-op | RED — unit test and integration suite |
| Wrong resolution (100ns instead of 1µs) | RED — unit test and integration suite |

**Determinism:** three consecutive `cleanTest` runs of the three affected suites — 36 tests, 0 failures, every
time.

## 13. Security regression assessment

No security control was touched. Verified after the change:

- ADR-025 still states `DENY_ALL` only, `ALLOWLIST` modelled and refused at both the control plane and the
  runner, no proxy. Confirmed in code, not just in prose.
- Sandbox controls unchanged: network disabled, read-only root, bounded tmpfs, no bind mounts, dropped
  capabilities, no-new-privileges, non-root, memory and CPU limits, PID limits, bounded logs and output,
  launcher deadline, cleanup on both paths.
- `hostile-execution-gate` green: **72 tests, 0 skipped** (floor 40).
- `synthetic-execution-pipeline` green: **24 tests, 0 skipped** (floor 20).
- No Karate, no secret resolution, no tenant content execution, no egress.

## 14. CI workflow assessment

Unchanged. The six jobs remain separate; the Docker-heavy suites were **not** collapsed back into `backend`.
Both gates keep `cleanTest`, executed-count floors, zero-skip assertions, named required suites, and failure —
not skipping — when prerequisites are absent. No `continue-on-error`, no job-level skip conditions, no
Docker-availability branching.

## 15. Documentation drift found

- `IMPLEMENTATION_STATUS.md` still described "repository reality after the dispatch consumption, claim, and
  lease slice" — two slices stale.
- It listed "Non-executing runner bootstrap with a behavioral test of its disabled-execution message" while the
  runner drives the full lifecycle.
- It described the outbox digest as making duplicates safe "for a future consumer"; the consumer exists.
- `README.md`'s architecture diagram marked RabbitMQ, the worker, and the sandbox as `proposed` — all three are
  implemented — and its accompanying text said execution "remains disabled" without distinguishing tenant
  content from the platform-owned workload.

## 16. Documentation changes made

- `IMPLEMENTATION_STATUS.md`: preamble now names the synthetic execution lifecycle slice and this stabilization
  pass, and states plainly that the repository executes a platform-owned workload and no tenant content; the
  runner entry describes what the runner actually does, including what it still refuses; the outbox entry no
  longer calls the consumer future.
- `README.md`: the diagram's implemented edges are solid and the unbuilt one stays dotted, with a legend saying
  which is which; the text distinguishes tenant content from the synthetic workload and names the two
  prerequisites (ADR-022 runtime, ADR-025 egress) that still gate tenant execution.

## 17. Remaining documentation limitations

- `README.md` still marks the OpenAPI contract `IMPLEMENTED + PROPOSED`, which is accurate: events, results,
  and artifacts remain proposed paths.
- Object storage remains the one dotted edge in the diagram; nothing implements it.
- The slice reports for earlier slices are historical records and were deliberately not rewritten — they
  describe the state at the time they were written, and ADR-023's status note already points forward to
  ADR-024.

## 18. Branch protection and required-check verification

**Could not verify classic branch protection with available credentials.** The workflow defines six jobs; which
of them the default branch actually requires is repository administration state, not repository content, and
was not modified.

These six job names should be configured as required checks:

```
backend
hostile-execution-gate
synthetic-execution-pipeline
web
contracts
infrastructure
```

`synthetic-execution-pipeline` is new as of the previous slice and is intended to be mandatory. A required
check is satisfied by `success`, `skipped`, **or** `neutral`, which is why both gates fail rather than skip when
their prerequisites are missing.

## 19. Full local verification matrix

| Step | Result |
|---|---|
| `clean check -x :services:runner:test -x :tests:pipeline:test` | **SUCCESS** — 273 tests, 0 skipped |
| `:services:runner:cleanTest :services:runner:check` | **SUCCESS** — 72 tests, 0 skipped |
| `:tests:pipeline:cleanTest :tests:pipeline:test` | **SUCCESS** — 24 tests, 0 skipped |
| `validate:schemas` | **SUCCESS** |
| `lint:openapi` | **SUCCESS** |
| web `lint` / `typecheck` / `test` / `build` / `audit --omit=dev` | **SUCCESS** — 0 failures, 0 vulnerabilities |
| `docker compose config` | **SUCCESS** |
| `git diff --check` | clean |

Both Docker-dependent suites genuinely executed against a real daemon and a real database.

## 20. Final GitHub Actions verification

Recorded in §24 after the push, from the workflow run triggered by the final commit. Local green is not
sufficient and is not being treated as sufficient.

## 21. Final commit SHA

Recorded in §24.

## 22. Deferred work

- An overdue gauge for the execution deadline reconciler, so a silently non-draining pass is alarmable
  (`found > 0 && stopped == 0`). Carried from the previous slice's residual risks.
- `DispatchConsumerInboxTests` uses unchecked operations. Left alone deliberately: it is test-only, the change
  is not trivially safe, and §11 forbids turning this into a modernization pass.
- The JNA native-access warning is a transitive dependency's, not this repository's.

## 23. Explicit confirmation that kaas-13 was NOT implemented

**No part of kaas-13 was implemented.** No egress proxy, no `ALLOWLIST` enforcement, no DNS resolution policy,
no address filtering, no redirect handling, no proxy authorization, no proxy lifecycle, no proxy image. The
eight requirements remain future prerequisites, stated in ADR-025 and §16 of the synthetic execution lifecycle
report. `NetworkPolicyType.ALLOWLIST` is still `false` for enforceability and is still refused independently at
both the control plane and the runner.

## 24. Final verdict

Pending the GitHub Actions result recorded here after push.
