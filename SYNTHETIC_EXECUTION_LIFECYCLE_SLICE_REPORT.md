# Synthetic execution lifecycle slice report

## 1. Executive summary

A run now executes. It moves `CLAIMED → PROVISIONING → RUNNING → COLLECTING_RESULTS → PROCESSING_RESULTS →
COMPLETED`, driven by a real worker over the internal HTTP API, running a real container under the hardened
profile, and finishing on a result the control plane accepts only after independently checking where it came
from. `EXECUTION_COMPLETED` is the first termination reason in this platform's history that means "finished
normally".

**What executes is a platform-owned synthetic workload, not a test engine.** That is the deliberate shape of
the slice: the execution lifecycle is where fencing, deadlines, partial failure, evidence, and reconciliation
all live, and none of those are engine problems. Composing them against a workload the platform wrote means a
lost result is debugged on its own, rather than alongside a Gherkin parser and a report format — and the
temptation under that combined load is to make the lifecycle lenient until the engine works.

365 tests across three modules, no failures, no skips. **27 of 27 applicable security mutations killed**; two
are not applicable because the egress proxy they target does not exist.

The slice found and fixed **thirty-two real defects**. Thirteen were found by the author, by running the
pipeline end to end, by mutation testing, and by reading the finished code for unreachable branches. The
remaining nineteen were found by eleven independent reviews, and those are the ones worth reading §29 for: they
include a defect that made every configured phase budget unreachable, a security test the slice had silently
deleted, and two published contracts still asserting an engine the platform no longer runs.

## 2. Scope

Delivered: command delivery, independent runner-side validation, runner authority revalidation, all four
execution phases with deadlines and a reconciler, cancellation from every owned phase, result submission with
provenance, evidence immutability, the migration and its evidence, ten reviews' worth of adversarial passes
folded into the work, the mutation battery, two ADRs, two documents, and CI that cannot silently skip any of it.

Explicitly out of scope by your direction, and honoured: no egress proxy, no weaker allowlist mechanism, no
FeatureRevision source execution, no production secret redemption, no Karate. `ALLOWLIST` remains represented
in the model and refused at runtime.

Out of scope by my own judgement, and flagged rather than silently omitted: worker heartbeating during
execution (§35), artifact retention, and infrastructure retry.

## 3. Command delivery

Worker-pull over the existing authenticated internal API rather than broker push. A command is authority, and
pushing authority to a queue means it exists in a place nobody has revalidated against live state at the moment
it is consumed. The worker asks, and the asking *is* the revalidation.

`POST /internal/v1/runs/{runId}/attempts/{attemptId}/execution-authorizations` returns the command; the two new
endpoints are `.../phases` and `.../results`. All three sit on the internal filter chain behind
`ROLE_KAAS_SERVICE`, are absent from the public OpenAPI document, and take no organization from the caller —
the run's own organization is read from the run, because accepting it from the caller would be accepting a
claim about ownership from the party whose ownership is in question.

Every refusal is `409` with the reason in the body. One status for all of them, so the endpoint is not an
oracle; the body carries the code because the caller is the platform's own worker and must act differently on
each.

## 4. Runner trust boundary

The runner holds container-runtime access and is the most privileged code in the repository. What it may do is
bounded structurally rather than by convention:

- Its build fails if it acquires Karate, an object store, or a secret provider.
- The control plane's build fails if it acquires a container runtime.
- Neither module can depend on the other, which is why the full-pipeline test needed a third module.
- `ValidatedCommand` carries no field that could hold tenant content, and `ControlPlaneClient` exposes no
  method that could fetch it (§18).

Jackson was added to this module for command and result JSON. It is not on the forbidden list and does not
weaken any of the above.

## 5. Runner authority revalidation

Authority is revalidated **before anything is provisioned** — provisioning is the first step that costs
something and the first that can leave a container behind. The claim that won the assignment may be minutes
old; the run may have been cancelled, the lease may have lapsed, the attempt may have been reassigned.

The control plane's own revalidation checks, in order: the run still names this attempt; an assignment exists;
worker identity **and** epoch together; whether the run is stopping; whether the assignment is fenced; whether
the lease is live. Identity and epoch are never checked separately — a reassignment to the same worker after a
fence produces a request where the identity matches and the epoch does not, and both halves are mutation-tested
independently (§30).

## 6. CLAIMED → PROVISIONING

Announced **before** the sandbox exists. Announcing afterwards leaves a window in which a container is running
and no deadline covers it, and a run recorded as `CLAIMED` with a live container behind it is an orphan nobody
is looking for. Reporting the phase first means a runner that dies leaves a run at worst one step ahead of
reality — which a reconciler can act on.

The transition is a compare-and-set on state *and* version, under a row lock, with the authoritative clock read
**after** the lock. Reading it before was a real defect on the capability path in an earlier slice: a request
that waited on a contended row evaluated its windows against an instant from before the wait.

## 7. Provisioning deadline

Two minutes. Provisioning that is slow is provisioning that is broken. Armed by the transition itself and
enforced by `CHECK ((lifecycle_state IN (...)) = (phase_deadline_at IS NOT NULL))`, so a transition that forgot
to arm the deadline is refused rather than producing a phase with no bounded exit.

## 8. Hardened sandbox composition

Unchanged from ADR-022 and deliberately so. The workload runs through the same `DockerSandboxLauncher`, the
same immutable `SandboxSecurityProfile`, and the same digest-pinned repository-controlled image as the security
probe. The sandbox does not get a second, softer entry point because what it is running happens to be benign.

The runner passes the **sandbox profile version** from the command. Passing the engine version instead was a
defect this slice introduced and the launcher caught, refusing with "Unknown security profile version" — the
launcher declining to run under a profile it does not recognise.

## 9. PROVISIONING → RUNNING

The only transition that stamps `execution_started_at`, and it stamps the transition's own instant rather than
an arbitrary earlier one. That instant is returned to the worker and echoed back in the result, because the
provenance check requires exact equality and a locally measured start would differ by however far the two hosts
have drifted (§22).

## 10. Execution deadline

Thirty minutes — the only phase whose length is a property of the test rather than of the platform. It is not
tenant-configurable: it bounds how long the platform holds admission capacity for a worker that has stopped
talking, which is a capacity decision.

**The lease now spans it, and did not when this was first written.** `WorkerLeaseService` refused a heartbeat
unless the run was `CLAIMED`, so renewal became impossible the moment a worker entered `PROVISIONING` — and
with the shipped thirty-second lease, every run longer than half a minute was refused mid-flight and then
recorded as having timed out during execution. Both halves of that diagnosis were false: the workload had
finished, and nothing had timed out except a lease nobody was permitted to renew.

The suite could not see it, for two compounding reasons: the pipeline test overrode the lease to two minutes,
and the synthetic workload finishes in a fraction of a second. The override was the symptom. `LeaseRenewalPipelineTests`
now runs with a deliberately short lease, spans more than one period, and carries an anti-vacuity twin proving
the lease genuinely expires without renewal.

## 11. Cancellation

Reaches every owned phase up to `COLLECTING_RESULTS`. Previously only `CLAIMED` was cancellable; a phase a
tenant cannot stop is one where a stop request silently does nothing until a deadline expires, and the longest
of those is the execution budget — exactly when someone most wants to stop.

`PROCESSING_RESULTS` is excluded from *tenant* cancellation: the execution is over, the sandbox is gone, and
cancelling would discard evidence already paid for. The platform can still reclaim it, so it is not a trap
state.

Extending this required fixing three layers, and I found them one at a time: the service's cancellable set, the
repository query that decides whether a cancellation finds anything at all, and the compare-and-set that
assumed the previous state was `CLAIMED`.

## 12. Fencing

The assignment epoch is the fencing token, and every path into `STOPPING` fences the assignment in the same
transaction that stops the run — tenant cancellation and deadline expiry alike. The deferred scheduling-bundle
constraint enforces that a `STOPPING` or `COMPLETED` run holds no live assignment.

A worker whose run was stopped is told `RUN_STOPPING`, not `ASSIGNMENT_STALE`. Both are technically true once
the fence lands, but "somebody else has this now" is false and misleading: nobody else has it, the run was
stopped. Mutation testing forced this: while the fence answered first, deleting the stop check changed no
observable behaviour and no test could tell (§30).

## 13. Lease behaviour

Expiry is checked as a fact separate from fencing. A lease can lapse without anyone having fenced it yet, and
the reconciler that would do so runs on its own schedule — checking only the fence flag would let a worker keep
driving a run for as long as the reconciler was behind. The database enforces the same independently: an
execution-history write requires `OLD.lease_expires_at > clock_timestamp()`.

Because both refuse with `409`, the test asserts the refusal **code** rather than the status. Asserting the
status alone passed with the service's own lease check deleted.

## 14. Network DENY_ALL

The only enforceable policy, applied by the container runtime rather than by the workload cooperating, and
asserted from inside the sandbox by the hostile-execution probe on every gate run.

## 15. Network ALLOWLIST

Modelled, and **refused at two independent points**: the control plane denies authorization with
`NETWORK_POLICY_NOT_ENFORCEABLE`, and the runner refuses the command in a module that structurally cannot call
the control plane's code. Refused rather than degraded, because a run that appeared to have egress control
nothing was applying would be worse than one with none — somebody would rely on it.

The runner's refusal was, until this slice, **covered by nothing**. Every negative test tampered with a real
command, which changed its digest, so the digest check refused first and the policy check below it was never
reached. Deleting it killed no test. Fixed by building correctly-digested-but-unenforceable commands.

## 16. DNS/SSRF model

There is no egress, so there is no SSRF surface today. The model is recorded now because the design that must
*not* be built is the obvious one, and it will look reasonable to whoever picks this up: validate the URL in
application code, then let the sandbox make the request normally.

That cannot be made safe. The check and the connection are separated in time, so a zero-TTL record alternating
between a public address and `169.254.169.254` passes validation and connects wherever it likes; and the
sandbox retains a normal route, so nothing forces traffic through the check at all.

### The shape that can work

The sandbox attaches to a Docker `--internal` network with no default route. A trusted proxy attaches to that
network **and** to one that can reach targets. The sandbox cannot route around the proxy because there is no
other route — a property of the topology, not of anything the workload agrees to.

### What kaas-13 must satisfy before ALLOWLIST can be enforceable

These are requirements, not suggestions. An implementation that does not meet **all** of them is not an
allowlist, and each carries the test that decides whether it holds. Also recorded in
[ADR-025](docs/adr/025-execution-egress-remains-deny-all.md) and
[the egress policy document](docs/security/execution-egress-policy.md); they are stated here in full because
this report is the artifact that has to be readable on its own.

**1. Proxy bypass — the sandbox must have no second route.**
No default route, no DNS resolver, no host networking, no secondary interface. The proxy is the only reachable
peer. *Test:* with the proxy stopped, every outbound attempt from the sandbox fails. If anything succeeds there
is a second route and the entire design is void — this test comes first because every other requirement assumes
it.

**2. DNS rebinding — the proxy resolves and connects in one step.**
The sandbox must not resolve names. If the sandbox resolves and the proxy connects, or the proxy resolves
twice, a name whose answer changes between lookups defeats the check. A zero-TTL record alternating between a
public address and `169.254.169.254` passes any validation performed before connection. *Test:* a target whose
DNS answer changes between check and connection is refused, driven by a real authoritative server serving the
alternating answers — **not a mock**, because a mock proves only that the code handles the case somebody
already thought of.

**3. Private and reserved addresses — refused on the RESOLVED address, never the hostname.**
Minimum denied set: RFC 1918, loopback, link-local `169.254.0.0/16` (and therefore every cloud metadata
endpoint), unique-local IPv6, IPv4-mapped IPv6, `0.0.0.0/8`, carrier-grade NAT `100.64.0.0/10`, multicast, and
broadcast. IPv6 must be handled explicitly or disabled outright — a v4-only check on a dual-stack host is not a
check. *Test:* a hostname resolving to a private address is refused, including the **IPv4-mapped IPv6 form**,
which is the case a naive implementation misses.

**4. Redirects — every hop re-validated.**
A redirect is a destination chosen by the target, not by the tenant. Each hop must be re-checked against the
allowlist *and* against requirement 3, with a bounded hop count; the safest implementation does not follow
redirects at all and returns them to the caller. *Test:* an allowed host redirecting to a denied host, and one
redirecting to a private address, are both refused **at the redirect** rather than followed.

**5. Assignment-scoped proxy authorization.**
Proxy credentials bind to one run, one attempt, and one assignment epoch, and are revalidated against live
state on every request — the same discipline the source capability already follows. A shared proxy credential
means any sandbox can spend any other sandbox's allowlist, and a credential checked only at issuance means a
fenced worker keeps its egress. *Test:* a fenced assignment's proxy credential is refused **mid-run**, not
merely at issuance.

**6. Network lifecycle tied to the sandbox's.**
The proxy and its network start before the sandbox and are destroyed after it, on **every** path: normal
completion, launch failure, deadline expiry, cancellation, infrastructure failure, and reconciler reclaim. A
proxy outliving its sandbox is a standing egress gateway with nothing left to authorize it. The orphan
reconciler must reclaim proxies and networks on the same age basis it reclaims sandboxes — by age, never by
generation, for the reason ADR-022 records. *Test:* kill the runner mid-execution and assert the reconciler
reclaims the container, the proxy, **and** the network.

**7. Failure and reconciliation semantics defined before the happy path.**
A proxy that will not start is an `INFRASTRUCTURE_FAILURE`, not a test failure — the run stops with no test
outcome, through the endpoint this slice added. A proxy that dies mid-run must not restore connectivity, which
the `--internal` network guarantees structurally rather than by detection. Egress denials must be attributable
in the result document without leaking the resolved addresses of one tenant's internal infrastructure into logs
another tenant's operator can read. *Test:* kill the proxy mid-run and assert the sandbox loses connectivity
rather than gaining it.

**8. The proxy image is repository-controlled and digest-pinned.**
Same rules as the probe image: built from a repository-controlled context, pinned by digest, and declared as a
Gradle task input so a change to it cannot leave the test task `UP-TO-DATE`. That last clause is not
bookkeeping — it is the difference between a suite that re-runs when the proxy changes and one that reports
green over a stale image.

**Two mutations in this slice's battery are marked NOT APPLICABLE for exactly this reason** (§30, rows 07 and
08): private-address rejection and redirect policy have nothing to mutate, because neither exists. They become
applicable the day requirement 3 and requirement 4 land, and the battery should fail closed until they do.

## 17. Trusted synthetic workload

`KAAS_SYNTHETIC_V1`. Three deterministic assertions in the trusted probe image, plus a deliberate-failure
variant so the `FAILED` terminal outcome is reachable — without it that transition would ship having never
executed, and the first genuine test failure in production would be the first time that code ran.

**It exits zero even when its assertions fail.** A failing test is not a failing execution: the infrastructure
ran the workload and collected the result. Had the exit code tracked the test outcome, every red test would be
indistinguishable from a broken sandbox.

It never claims to be an engine it is not. A mutation making it report `KARATE_1_4_1` turned three tests red,
including the one named for exactly that.

## 18. Source non-use

Structural, not asserted. The execution loop consumes a `ValidatedCommand`, and that type carries no source
content — not the bundle, not the features, not their digests. There is nothing to hand a sandbox because the
value does not exist at that point in the program.

`SourceNonUseTests` pins the shape by reflection rather than ArchUnit, because the claim is about the shape of
a type rather than a package dependency: a dependency rule would say the execution package imports no source
class, and say nothing about a `String featureSource` field appearing on the command the loop already holds. It
also asserts that `ControlPlaneClient` declares no method that could fetch a bundle, and that no runner source
file names the `/source-bundles` endpoint or its capability header — both of which are strings a dependency
rule would never catch. Adding a source-bearing field to `ValidatedCommand` turns it red.

## 19. Secret non-use

The control plane refuses any secret-bearing run at authorization, because no production provider exists. The
runner refuses a second time: a command carrying any secret capability is rejected outright as "describing
something that cannot have been issued honestly".

## 20. RUNNING → COLLECTING_RESULTS

A separate phase from processing because they fail differently: collection happens while the sandbox still
exists, processing after it is gone. Collapsing them would make "the sandbox died before we read the results"
and "we could not digest what we read" the same incident.

## 21. Result contract

The existing `runner-result.schema.json`, not a second format. One contract change was necessary and is
recorded in the schema itself: `artifactManifestReference` is now optional, because `objectReferenceId` must be
a control-plane-issued storage reference and **no object store exists**. The alternatives were fabricating an
`object-ref:` that any consumer would try to fetch, or reporting a complete execution as incomplete. Covered by
a new fixture.

The document reports **zero** features and zero summary counts, because those describe the tenant's suite and
none of it ran. Populating them with the workload's own assertions would attribute results to features that
never executed — the same lie as misreporting the engine, further down the document. `producer` is
`kaas-runner-synthetic`.

## 22. Result provenance

| Field | Compared against |
|---|---|
| run, attempt, epoch | the locked run and its current attempt |
| command | `execution_commands`, scoped by attempt **and** epoch |
| run version | the version the **command** was issued for |
| snapshot | the run's own sealed `snapshot_sha256` |
| started at | the instant the control plane stamped on entering `RUNNING` |
| finished at | must not precede started at |

Two of these were initially worthless. The command was compared only against the document's own copy — two
fields the same caller supplies, which agree whatever value was chosen. And the run version was compared
against the run's *current* version, which refuses every honest result, since the run advances four times
between authorization and submission and the worker cannot know where it got to.

### Idempotency, and a branch that could not be reached

Accepting a result completes the run, and completing it fences the assignment. A worker that submitted
successfully and lost the response therefore comes back holding an assignment that is by then legitimately
fenced — and with liveness checked first, it was told `ASSIGNMENT_STALE`.

That is the worst available answer. The worker concludes it lost the run and reports a failure that never
happened, while its result sits accepted in the database. `RESULT_ALREADY_SUBMITTED` existed for exactly this
case and **was unreachable**, both before this slice and after the reordering in §12.

Revalidation is now split into proving the assignment and checking whether it may still act, so each path can
put its own question between the two. The result path asks "have you already submitted?" after identity is
proved and before liveness — safe precisely because identity is proved, so the answer goes to the assignment it
is about. Mutation 15 moves the check back and turns the test red.

This one was found by reading the finished code and asking which branches could actually execute. Neither the
pipeline run nor the mutation battery would have found it: the battery only mutates code, and code that is
already unreachable survives every mutation of itself.

## 23. PROCESSING_RESULTS

Control-plane work after the sandbox is gone, so it stamps nothing on the attempt. It is the only state from
which `COMPLETED` may carry a real test outcome, and the transition writes evidence and completes the run in
one transaction: evidence without completion describes something still believed to be running, and completion
without evidence is what the database's trigger refuses.

## 24. Final outcomes

Orthogonal, enforced in three places — the run, the result row, and the runner's own domain record:

```sql
CHECK ((infrastructure_outcome = 'SUCCEEDED') = (test_outcome IN ('PASSED','FAILED')))
```

`EXECUTION_COMPLETED` pairs with `SUCCEEDED`; the three deadlines with `TIMED_OUT`; `LEASE_LOST` and
`INFRASTRUCTURE_FAILURE` with `FAILED`; `USER_REQUESTED` with `CANCELLED`. Every stopped run settles with
`NOT_AVAILABLE`, because nothing produced a result.

## 25. Deadline reconcilers

One reconciler for all four phases, not four. They differ only in which reason they record; four timers
scanning one partial index for four disjoint predicates would be four times the work to answer one question,
and the three that fired least often would be the three nobody noticed had stopped.

Overdue-ness is evaluated against the database's own clock, in the database — the deadline was armed by that
clock, and comparing it to another makes expiry depend on two hosts agreeing. Each run is re-checked under its
own lock, because the scan is a hint about the past and timing out work that just succeeded is worse than
timing it out a pass late.

Each reason is paired with the phase it can expire in, so a reconciler cannot record a provisioning timeout
against a run that was executing.

**Two defects here were failing silently.** The reconciler never fenced the assignment, so every deadline stop
failed at commit against the deferred bundle constraint — invisibly, because the per-run catch exists so one
bad run cannot abandon the batch. And its `@Transactional` did nothing: `stop()` was called by self-invocation
from `reconcile()`, bypassing the proxy, so the two writes auto-committed separately. Replaced with an explicit
`TransactionTemplate`. The failure counter was the only evidence either was broken, which is exactly why it
exists.

## 26. Orphan reconciliation

Unchanged from ADR-022 and still judged by **age, not generation**. Generation-scoping was previously observed
force-removing running containers belonging to a live sibling launcher. Removing the age guard turns
`aRunningSandboxFromAnotherLiveLauncherIsNeverReclaimed` red.

I did not mutate the managed-label filter. With the age guard also removed it could reclaim unrelated
containers on the machine running the battery, and that is not a risk worth taking for a test result.

## 27. Persistence

`V10`, 908 lines. New: `phase_deadline_at` and `execution_started_at` on `test_runs`; execution history on
`execution_attempts`; the `execution_results` table — immutable, un-truncatable, bound to run, attempt, epoch,
command, and snapshot, unique per `(attempt_id, assignment_epoch)`; and `require_execution_evidence`, which
refuses a run completed via `EXECUTION_COMPLETED` without its matching result.

**Four guards were rewritten as a unit**, every one found by running the pipeline rather than by inspection:

| Guard | What it refused |
|---|---|
| `guard_supported_test_run_update` | every new transition |
| `guard_execution_attempt` | every write to the new history columns |
| `require_complete_scheduling_bundle` | all four phases, as "not an implemented state" |
| `ck_run_lifecycle_events_transition` | the new edges |

`guard_execution_attempt` gained a property worth naming: the columns an assignment transition may move and the
columns a history write may move are **disjoint by construction**, so no statement can do both. A worker
reporting progress cannot renew its own lease as a side effect.

`ck_run_snapshots_engine` was widened from `= 'KARATE'` to `IN ('SYNTHETIC','KARATE')` (§36).

## 28. Migration evidence

`V10` transforms validation over existing rows: five constraints are dropped and re-added, so each is validated
against every row already present. It takes `ACCESS EXCLUSIVE` on four tables and full-scans them; this is a
maintenance window, not a rolling upgrade, and `lock_timeout` makes it fail fast rather than queue.

The fixture was strengthened because the rule requires it. It had **no row with a non-null `stop_reason`**, so
the rewritten stop-reason CHECK would have been validated against nothing; a `STOPPING` run under a `FENCED`
assignment was added, seeded `LEASE_LOST` so it carries a stop reason without cancellation timestamps. All nine
snapshot rows say `KARATE`, which is the point: the engine widening has to be evaluated against rows carrying
the old value.

The engine constraint is asserted from `pg_constraint`, **not** by offering it a bad value. Snapshots are
immutable, so an `UPDATE` is refused by the immutability trigger long before the CHECK is consulted — a test
written that way would pass while proving nothing about the CHECK.

"Invented nothing" assertions were changed from absence to exact counts rather than deleted.

## 29. Security review

**Eleven independent reviews were run**, read-only and without builds — because the contamination in an earlier
slice (a debug statement left in production source; a phantom finding traced to another reviewer's temporary
harness) came from concurrent reviewers writing to one shared tree. Every finding below was verified against the
code before being acted on.

Dimensions: lifecycle guard completeness; fencing and epoch; result provenance and digests; deadlines and
reconciliation; transactions and atomicity; tenant isolation and disclosure; runner trust boundary;
truthfulness of reporting; test quality and vacuity; migration safety; and unreachable code — the last added
because the one defect found by hand was an unreachable branch, and mutation testing is structurally blind to
that class.

**They found nineteen defects the author did not**, including four the author had reported as sound:

| What the reviews found | Why the author missed it |
|---|---|
| Heartbeating is refused in every execution phase, so no budget is reachable | the author's own test widened the lease to four times production |
| A security test had been deleted by an over-matching regex | the full suite stayed green; the control it covered is covered by nothing else |
| `KARATE` survives in two published contracts | the author edited one block and never re-read the schemas |
| `INFRASTRUCTURE_FAILURE` has no writer at all | the author's own comment asserted the route existed |
| The runner discards the refusal code the control plane went to trouble to return | the author had just "fixed" that path and reported it done |
| A bare `clock_timestamp()` four functions below the author's own comment explaining why it is wrong | — |
| A missing `coalesce(..., false)` two constraints below two that have it | — |
| `infrastructure_disposition` could structurally only ever say SUCCEEDED | — |

**The pattern worth recording**: the author widened one layer without widening the layer beneath it **three
separate times** — the lifecycle guard, the cancellation path, and the scheduling bundle — believing the change
complete each time. That is precisely the failure a second reader catches by recognising the first instance,
and precisely the failure that repeated verification by the same person does not.

**Two things the reviews confirmed rather than faulted**, which matter as much: the migration is safe against
populated production data with no P0, and the guard enumeration the author was least confident about was in
fact complete — there is no fifth missed guard. Lock ordering has no inversion, tenant/service chain separation
holds in both directions, and the two digest implementations agree field-for-field, which is the evidence the
deliberately-duplicated implementation was built to produce.

Disclosure is bounded. Every internal refusal is `409` whatever the reason, so the endpoint is not an oracle;
anything more specific than `ASSIGNMENT_STALE` is disclosed only after the caller has proved it holds the
assignment. `DATABASE_CONSTRAINT_VIOLATED` logs SQLSTATE and exception type but **never** the driver's message,
because PostgreSQL embeds the failing row's values in constraint text — and the result reader now logs the
exception type only, for the same reason, after a review found it echoing a worker's entire submitted field.

## 30. Mutation evidence

Two-axis battery, harness reproducible. **27 of 27 applicable killed; 2 not applicable.**

| # | Mutation | Result |
|---|---|---|
| 01 | runner authority revalidation removed | KILLED |
| 02 | epoch check removed | KILLED |
| 02b | worker identity check removed | KILLED |
| 02c | assignment-acquisition requirement removed | KILLED* |
| 03 | lease check removed | KILLED* |
| 04 | command digest verification removed | KILLED |
| 05 | strict unknown-property rejection removed | KILLED |
| 06b | network policy enforceability removed | KILLED* |
| 06c | engine enforceability removed | KILLED* |
| 07 | private-address rejection | **NOT APPLICABLE** — no egress proxy exists |
| 08 | redirect policy check | **NOT APPLICABLE** — no egress proxy exists |
| 09 | attestation verdict check removed | KILLED |
| 09b | absent attestation no longer fail-closed | KILLED |
| 10 | cancellation fencing removed | KILLED* |
| 11 | result epoch check removed | KILLED* |
| 11b | issued-command check removed | KILLED* |
| 12 | orphan reconciler age scope removed | KILLED |
| 13 | execution timeout removed | KILLED |
| 14 | source non-use guard removed | KILLED |
| 15 | result idempotency reordered behind liveness | KILLED |
| 16 | attestation unknown-property allowlist removed | KILLED* |
| 17 | heartbeat restricted to CLAIMED again | KILLED* |
| 18 | lease fencing restricted to CLAIMED again | KILLED* |
| 19 | result-path identity proof removed | KILLED* |
| 20 | deadline phase-to-reason mapping collapsed | KILLED* |
| 22 | missing-workload-outcome check removed | KILLED* |
| 23 | workload identity check removed | KILLED* |
| AV | ArchUnit package misspelled | KILLED |
| AV3 | ArchUnit SUB-package selector misspelled | KILLED* |

`*` = **survived on the first attempt**, then a real gap was closed. That is the battery's whole value, and the
list is longer than it was before the reviews:

- **06b/06c**: every negative test tampered with a command, so the digest refused first and the policy and
  engine checks below it were reached by nothing.
- **03**: service check and database condition both return `409`; the test asserted status, not code.
- **11/11b/19**: negative tests submitted `{}`, which fails to parse before any provenance field is compared —
  and one test invented two different UUIDs, so they disagreed with each other and the wrong check refused it.
- **02c/16/17/18/20**: each is a control added or restored during the review fix pass whose first mutation
  showed no test reached it. Every one now dies to a specifically-named test.
- **AV3**: the anti-vacuity guard held its OWN copy of each package name, so misspelling a selector in a rule
  never tripped it — it could only catch a mistake made in two places at once. The rules and the guard now
  share one constant, which is what makes the guard work at all.

**One mutation was deleted rather than killed.** `evidenceIsComplete()` is `failure.isEmpty() || timedOut()`,
and the check above it already returned whenever a failure was present — so it could never fire. It was
unreachable code wearing the shape of a control, and removing it is the honest outcome.

**One jointly-covered pair was split rather than documented.** The workload-identity check and the
missing-outcome check each refused the other's scenario, so deleting either killed nothing. Two probe modes now
produce exactly one defect each — a correct identity with no verdict, and a confident verdict under the wrong
identity — and each check dies to its own test.

Two harness bugs were themselves found and fixed: zsh does not word-split unquoted parameters, so a multi-task
target became one invalid task name and reported `red=0` as a survival; and stale results from a previous
mutation were counted as kills. A run that executes no tests now fails loudly instead of reporting a survival.

## 31. QE evidence

365 tests, 0 failures, 0 skips: `:apps:api` 269, `:services:runner` 72, `:tests:pipeline` 24.

`:tests:pipeline` is a new module and exists because neither other module can host the full-pipeline test —
each is build-guarded against the other, and those guards are why the launcher may talk to a Docker daemon at
all. It declares the probe build context and the migration directory as task inputs, because both live in other
modules and Gradle would otherwise leave the suite `UP-TO-DATE` after a schema or probe change.

A latent time bomb was also defused: a gate fixture carried a hardcoded assessment date that aged past its
24-hour freshness maximum and went red on 2026-09-03. The failing test was the guard written to catch exactly
that. Resetting the date would re-arm it; the fixture now generates and digests itself.

## 32. CI evidence

`hostile-execution-gate` is unchanged and unweakened, with its required-suite list extended to
`SyntheticWorkloadTests`, `SourceNonUseTests`, and `UnenforceableCommandTests`.

A new required job, `synthetic-execution-pipeline` — globally unique, no `if:`, no `continue-on-error`, no
filters, because a required check is satisfied by success, **skipped, or neutral**, so a job that skips itself
when Docker is absent is indistinguishable from one that verified everything. It runs `cleanTest`, asserts a
floor of 12 executed tests and **zero** skips, and names the suites that must be present. Both Docker-heavy
suites are excluded from `backend` so two launchers never share one daemon.

## 33. Files changed

65 files: 25 new, 40 modified. New: `V10`, the `com.kaas.api.execution` lifecycle package (repository, phase
service, result service, reconciler, JDBC, reader), the internal lifecycle controller, the runner's command
validation and execution packages, the `:tests:pipeline` module, two ADRs, two documents, and this report.

## 34. Verification

`./gradlew clean check` green across all three modules; contract schemas and OpenAPI lint green; the mutation
battery as above; no mutants left in the tree, verified by grep.

## 35. Residual risks

1. **Worker identity is per-process but the fleet is shared.** Assignment acquisition binds a run to one
   authenticated worker, write-once, so two workers can no longer both satisfy every ownership check and the
   epoch is a real fencing token again. What it does **not** create is a tenant boundary: workers consume one
   queue and carry no tenant scope, so any worker may legitimately acquire any run. That is the architecture,
   not an oversight — but it means a compromised worker can affect any run it acquires first, and the mitigation
   is that its actions are now attributable and individually revocable rather than indistinguishable.
2. **Shared-kernel Docker.** ADR-022's runtime prerequisite is unmet. It does not gate this slice, because the
   workload is repository-controlled, and it absolutely gates tenant content.
3. **The attestation is deployment-scoped while the property it attests is host-scoped**, and is unsigned.
   Unchanged from ADR-023.
4. **One attempt per run.** Infrastructure retry does not exist. Two places would need attention the day it
   does: `require_execution_evidence` counts results by attempt without an epoch, and the results table permits
   one result per epoch — so two epochs with identical outcomes would make a run uncompletable.
5. **`V10` is a maintenance window**, not a rolling upgrade. It drops and re-adds seven constraints, adds six
   more, and builds an index non-concurrently, all under `ACCESS EXCLUSIVE`.
6. **A synthetic result is distinguishable only by `producer`.** The document reports zero features and
   `kaas-runner-synthetic`, and the snapshot's engine says `SYNTHETIC` — but a consumer reading `testOutcome:
   PASSED` alone would see green. A first-class engine field on the result contract is the durable fix and needs
   a contract change, because `additionalProperties: false` forbids adding one today.
7. **`executionTimeoutSeconds` is accepted, validated, and digested, and nothing enforces it.** The sandbox's
   wall-clock ceiling comes from the immutable security profile. It is deliberately not carried onto the
   validated command, so the gap is visible rather than plausible — but a tenant can still set a value that
   does nothing.
8. **A silently non-draining deadline reconciler is still hard to see.** The failure counter fires only on a
   thrown exception, not when `stopOverdue` legitimately returns false for every run in a batch. An overdue
   gauge, or a `found` counter beside `stopped`, would make `found > 0 && stopped == 0` alarmable.

## 36. Exact blockers before Karate

1. **Enforceable egress** — all eight requirements enumerated in §16: no second route, DNS resolution and
   connection in one step, private and reserved addresses refused on the resolved address, every redirect hop
   re-validated, assignment-scoped proxy credentials revalidated per request, proxy and network lifecycle tied
   to the sandbox on every failure path, failure semantics defined first, and a digest-pinned proxy image.
   Most suites are useless without it.
2. ~~Worker heartbeating~~ — **done in this slice.** A worker renews its lease for the whole of execution, and
   the lease reconciler can fence a lapsed one during any execution phase.
3. **A stronger runtime** (gVisor or a microVM) with the hostile-execution gate re-run against it, per
   ADR-022. This is what admits tenant content at all.
4. **Source delivery into a sandbox** — the bundle is currently fetched by nothing, and the runner's types
   cannot hold it. That is a deliberate boundary which will have to be opened deliberately.
5. **A real secret provider**, plus a delivery mechanism that does not put values in a command.
6. **Artifact storage**, and restoring `artifactManifestReference` to required once an object store can issue
   references.
7. **Engine result mapping**, replacing zeroed summaries with real feature, scenario, and step results.
8. **`EngineDescriptor` set to `KARATE`** and the runner's `EXECUTABLE_ENGINE` widened — which must be the
   *last* step, and must not happen before 1–7, because the moment it does the platform starts reporting Karate
   results it may not be producing.

## 37. Recommended next slice

**Enforceable execution egress (kaas-13), scoped exactly as ADR-025 states it.**

It is the largest blocker, the most dangerous component this platform will build, and the one whose failure is
silent — a broken allowlist passes every test the tenant wrote. It should be a slice of its own, with the eight
requirements as acceptance criteria and DNS rebinding tested against a real alternating resolver rather than a
mock.

**Worker heartbeating should come first if it can be split out**, because it is small, it is a correctness bug
today, and every long-running egress test will otherwise trip over the lease before it tests anything.
