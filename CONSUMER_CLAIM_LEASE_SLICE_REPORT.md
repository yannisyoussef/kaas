# Dispatch Consumption, Claim, and Lease Slice Report

## 1. Executive summary

The relay slice put dispatch intents on a broker that nothing consumed. This slice makes them arrive, be believed or refused on evidence, and — once believed — cause exactly one state change: `QUEUED → CLAIMED`, binding one worker instance to the attempt under an assignment epoch and a server-controlled lease.

Two things made this the security-sensitive slice so far. It is the first time the control plane reads bytes it did not author and has to decide what they may cause. And claiming creates the first state a run can be stuck in that somebody *owns* — which without a way to take ownership back would recreate the availability defect the early-terminal slice existed to remove, one state later. So the recovery paths are not follow-up work here; they are the precondition for enabling the consumer at all.

Still absent by design: no `ExecutionCommand`, no source capability, no secret capability, no provisioning, no Karate, no container.

## 2. Consumer semantics

A message is transport, never authority. Validation runs in a fixed order, each step cheap enough to run before the one that would be expensive to run on garbage:

1. size, before anything parses it
2. transport headers — **reject-only**, never accept-granting
3. strict UTF-8, rejecting rather than substituting replacement characters
4. strict JSON with `FAIL_ON_UNKNOWN_PROPERTIES` **enabled**
5. semantic digest re-derived from the parsed document and compared to the value it carries
6. contract type and version taken from the body
7. transport identity, if present, compared to the body's

Jackson defaults unknown-property failure to *false*. Leaving it there would let an attacker append fields the digest does not cover and that a future, more trusting reader might act on — and the relay slice already shipped a defect of the opposite shape, verifying a reduced projection while publishing different raw bytes. This is a known way to be wrong in this codebase.

## 3. Inbox design

Keyed by `(consumer, messageId)`. Not by delivery tag: that is channel-local transport metadata that changes on every redelivery and means nothing across connections, and the broker's `redelivered` flag is a hint rather than a statement about the message.

Dispositions are `CLAIMED`, `STALE`, `REJECTED`, `CONFLICT`. **`DUPLICATE` is deliberately not one of them** — a message's fate does not change when the broker offers it again, only the count of times it has been offered, so a redelivery increments `delivery_count` on the decision that already exists. A second decision row would mean the inbox held two answers to one question.

A refused message records no tenant identity, because the only source for one was the payload it just refused to believe.

## 4. Database-before-acknowledgement

```
broker delivery → transaction → COMMIT → broker ACK
```

Acknowledging first loses work on any process death between the two, with the broker believing it was handled. Committing first turns the same death into a redelivery the inbox absorbs. The listener returns only after the transaction commits, and the container acknowledges only when the listener returns.

## 5. Duplicate and redelivery behaviour

Proved directly: consume the same delivery three times, and the run's version, its lifecycle events, and its inbox row count all stay put while `delivery_count` reaches three.

**This is where a real defect was found.** The inbox stored a bare hex digest while the dispatch carried a `sha256:`-prefixed one, and the comparison was raw — so *every* redelivery compared unequal and was reported as an integrity conflict, which the listener dead-letters. Correct duplicates would have been routed to an operator queue and the inbox would have defeated its own purpose. Both sides are normalised now, and the test that found it asserts the disposition rather than merely the absence of a second claim.

## 6. Stale-message behaviour

A dispatch that arrives for a run cancelled or expired while it was in flight is recorded `STALE` and acknowledged. It is never dead-lettered: at-least-once publication racing a cancellation is the normal behaviour of the system, and an operator queue full of expected races is an operator queue nobody reads.

## 7. `QUEUED → CLAIMED`

One transaction: the run's compare-and-set, the assignment on its attempt, and the lifecycle event. A run that says it is claimed while its attempt says nobody owns it is the exact inconsistency the epoch exists to prevent, so the two never commit apart. The deferred bundle check enforces the same thing from below — a `CLAIMED` run must have exactly one attempt holding the active assignment.

Claiming after the queue deadline is refused. The reaper is entitled to end such a run, and a claim that slipped past it would leave two components each believing they hold it.

## 8. Worker identity

Server-controlled configuration, never a value from the message, and audit rather than authorization: it records which instance the control plane handed the assignment to, and decides nothing about what that instance may do. The claim's *audit actor* (`kaas.dispatch-consumer`) is separate from the worker instance recorded on the attempt, because "which service performed this transition" and "which worker owns this work" are different questions.

## 9. Assignment epoch

The fencing token. First claim is epoch 1; reassignment of the same attempt would require a strictly higher one, and an infrastructure retry would create a new attempt entirely — different things, kept apart by the schema. The epoch survives fencing rather than being cleared, because it is the record of which assignment was fenced and the bound a later one must exceed.

## 10. Fencing

Every operation checks identity **and** epoch. An epoch alone would let any worker pose as the current owner; an identity alone would let a restarted worker act under an assignment it has already lost. Fencing is what makes a partitioned worker harmless without the control plane having to reach it.

## 11. Lease model

Canonical profile: 30-second lease, 10-second heartbeat, 30-second recovery window after expiry before fencing. Every timestamp is server-controlled; nothing about a lease is accepted from a message. The window exists so a worker that misses one heartbeat to a garbage-collection pause or a brief partition is not punished for it.

## 12. Heartbeat

An internal operation on its own `/internal/v1` security chain, deliberately absent from the public contract. It bumps no version, emits no lifecycle event, and cannot renew an expired or fenced lease — renewing an expired one would let a worker take ownership back by being late rather than by being correct, and would undo the reconciler's basis for fencing.

The two surfaces have different authentication *shapes*, not merely different paths. A tenant token carries an `org_id`; a service token carries no tenancy at all, because a worker is not a tenant and every scope it touches comes from the run it names. A token carrying both a reserved `kaas.` subject and an organization is refused: a credential that is simultaneously a service and a tenant is a confusion waiting to be exploited.

## 13. Lease-expiry recovery

Expiry plus recovery window → fence the assignment and enter `STOPPING` in one transaction → settle on the next pass. Splitting fencing from the lifecycle move would leave a window in which the run is stopping while a worker still believes it owns the attempt.

Outcome is `NOT_AVAILABLE` / `FAILED` / `NOT_EVALUATED` with reason `LEASE_LOST` and phase `CLAIM`. **Not `TIMED_OUT`**: a queue deadline means the platform never got to the run, while a lost lease means it did and then lost the worker holding it. Not a cancellation either — nobody asked.

## 14. Cancellation race

Cancellation commits first → the dispatch is `STALE` and never claimed. The claim commits first → cancellation follows the owned path: the assignment is fenced, the run enters `STOPPING`, and the reconciler settles it as `CANCELLED`.

That makes the endpoint's `202` — "cancellation was durably requested and worker termination is pending" — reachable for the first time. It had been in the contract since the first slice and was removed as unreachable one slice ago; it comes back for exactly the case it always described.

## 15. Queue-timeout race

Decided by PostgreSQL commit ordering, never by a delivery timestamp or a worker clock. The reaper selects `lifecycle_state = 'QUEUED'` and the claim compare-and-sets on the same predicate, so whichever commits first makes the other's predicate stop matching. The claim additionally refuses to take a run past its deadline, so the two cannot both believe they won.

## 16. Database guard rewrite

The F-3 set moved as a unit again, this time to admit ownership:

| Guard | Now |
|---|---|
| `guard_supported_test_run_update` | schedule, claim, stop, two terminal shapes; everything else fails closed |
| `guard_initial_execution_attempt` → `guard_execution_attempt` | insert-only becomes claim, heartbeat, fence — and nothing else |
| `require_complete_scheduling_bundle` | branches for `CLAIMED` (exactly one live assignment) and `STOPPING` (assignment must be fenced), and a terminal run may retain none |
| `ck_run_lifecycle_events_transition` | six shapes, still `run_version = sequence + 1` |
| `guard_run_lifecycle_event` | matches non-terminal transitions against the run's own audit stamp |
| `guard_dispatch_inbox` | a decision is immutable; only redelivery accounting moves |

Actors are pinned per transition: `kaas.scheduler` schedules, `kaas.dispatch-consumer` claims, `kaas.lease-reconciler` fences and settles, and a tenant cancellation must **not** be a reserved name.

## 17. Broker dead-letter policy

Publisher terminal failure and consumer permanent failure are different problems and get different destinations. The dispatch queue now declares a dead-letter exchange, and only `REJECTED` and `CONFLICT` reach it. Stale messages are acknowledged.

**Deployment hazard, stated loudly:** adding the dead-letter arguments changes the queue's declaration, and RabbitMQ refuses to redeclare a queue with different arguments — it fails the channel with `PRECONDITION_FAILED`. An environment that already declared the queue must delete and recreate it. No deployment exists yet, which is the only reason this is safe now rather than under a new queue name.

## 18. Security boundary

No broker message alone grants execution authority. Claiming records ownership of the infrastructure attempt and issues no execution command, no source capability, and no secret capability. An ArchUnit rule fails the build if the claim path ever reaches a runner, Karate, an object store, a container launcher, or a secret provider, and a second rule keeps the dependency between the consumer and the control plane one-way.

The broker body is never logged, including on rejection: a message the consumer just refused to parse is untrusted input, and putting it in a log is how it reaches whoever reads the logs.

## 18a. What the tests were changed to actually prove

Seven independent reviews ran against this slice. Three of them broke the implementation to see whether the suite
noticed, and in three cases it did not — so the tests, not only the code, were the finding:

- **Strict unknown-property parsing.** Disabling `FAIL_ON_UNKNOWN_PROPERTIES` left the suite green: the extended
  body carried a random transport identity, so it was refused for that instead, and the reason assertion used an
  unordered `contains(...)` over a set that already held the reason. Every refusal now carries the identity its
  own body claims and its reason is read back keyed by that identity, and unknown properties have a reason code
  of their own rather than aliasing `MALFORMED_PAYLOAD`. Verified: disabling the flag now fails.
- **The lease recovery window.** Zeroing it left the suite green, because nothing sampled the band between expiry
  and fencing — the entire reason fencing is not immediate. The window is now widened enough to observe and
  asserted from inside it. Verified: zeroing it now fails.
- **Database-before-acknowledgement.** Nothing proved it. `QUEUE_MESSAGE_COUNT` counts *ready* messages, so a
  delivered-but-unacknowledged message is already absent and "the queue is empty" is satisfied from the moment of
  delivery. Acknowledgement is now observed positively through the management API's unacknowledged count, and a
  failure injected inside the transaction proves the work returns to the broker rather than being lost. Verified:
  `acknowledge-mode=none` now fails.

And the migration gate — the mechanism whose whole purpose is catching this class — had the same defect. Its
fixture held no terminated run, so every constraint V8 re-adds about terminated runs was validated against
nothing; a reviewer demonstrated that deleting a disjunct shipped green while failing against a database holding
one queue-reaped run. The fixture now seeds all three terminal shapes the previous version can produce, asserts
they are present before the upgrade runs, and was verified to go red on that exact deletion.

## 19. Test strategy

Real PostgreSQL and real RabbitMQ Testcontainers. Distributed behaviour — publication, consumption, redelivery, acknowledgement, dead-lettering — is proved against the real broker with the production consumer enabled. Control-plane invariants are driven through the use cases directly, because a message adds nothing to them and routing every assertion through a broker would only add nondeterminism.

Concurrency tests capture **every** racer's outcome, not just the final state: a test that checks only the end state cannot tell one clean winner from one winner and seven exceptions.

## 20. Failure injection

Malformed JSON, malformed UTF-8, oversized bodies, unsupported message type, unsupported schema version, unknown properties, tampered fields under an unchanged digest, transport identity disagreeing with the body, unknown message identity, cross-tenant and cross-project substitution, a second claim of an already-claimed run, a heartbeat under the wrong epoch, the wrong worker, the wrong attempt, a tenant credential, and a hybrid credential, a heartbeat after fencing and after termination, and a lease left alone while live.

## 21. Migration-upgrade evidence

V8 transforms no rows, but it adds validating CHECKs to `test_runs` and `execution_attempts`, and drops and re-adds the three constraints whose subject is a terminated run — all evaluated against every row already present. Replacing a guard *function* is deliberately not in that list: `CREATE OR REPLACE FUNCTION` validates nothing until the next write, so a broken guard is caught by the runtime suites, not by this gate. Claiming otherwise would credit the gate with coverage it does not have. The populated fixture is therefore asserted, **before** the upgrade runs, to hold attempts in `WAITING_FOR_CLAIM` and lifecycle events in the shapes the new transition CHECK reasons about; afterwards it is asserted that no run acquired a stop reason, no attempt acquired an assignment, and the inbox is empty. An upgrade that invented an assignment would be handing out ownership of work to a worker that never claimed it.

## 22. Observability

`kaas.dispatch.{claimed,stale,rejected,conflict,duplicate}`, `kaas.worker.heartbeat.{accepted,rejected}`, `kaas.worker.lease.expired`, `kaas.run.terminated`. Bounded reason categories only — never run, attempt, worker, organization, or message identity.

## 23. Files changed

Migration `V8__dispatch_consumption_claim_and_lease.sql`. New `consumer` package (domain, application, infrastructure) and `internal` package. Control plane gains `RunClaimService`/`RunClaimRepository`, `WorkerLeaseService`/`WorkerLeaseRepository`, `WorkerLeaseReconciler`, `WorkerAssignment`, `StopReason`, `ClaimDisposition`, and their JDBC adapters. `SecurityConfiguration` gains a second filter chain. `RabbitTopologyConfiguration` gains the dead-letter exchange and queue. Two new test classes; six existing suites updated.

## 24. Verification

Java 25 / Gradle 9.7.1, PostgreSQL 16.10 and RabbitMQ 3.13 Testcontainers. 154 API tests plus 1 runner test, zero failures, zero skips on `./gradlew clean check`. Contract, OpenAPI lint, web, Compose, and whitespace gates pass.

Four mutations were run to confirm the tests bite rather than merely pass: deleting a disjunct from the termination vocabulary turns the migration gate red; disabling strict unknown-property parsing turns the consumer suite red; zeroing the lease recovery window turns the claim suite red; and setting `acknowledge-mode=none` turns the database-before-acknowledgement test red.

**A fourth instance of the clock-authority defect, found by the suite rather than by a reviewer.** Two tests
failed intermittently under full-suite load with a generic `409` on a run that was plainly still `CLAIMED`. The
diagnostics added after the first occurrence identified it on the second: not a lifecycle refusal at all, but a
`DataIntegrityViolationException` rolling back the whole transaction.

The cause was the attempt guard's FENCE branch, the one arm no review flagged, bounding `fenced_at` by a bare
`clock_timestamp()`. A cancellation fences at the instant the run stops, and that instant is clamped up to the
tenant's request time, which comes from the application clock. Under Docker on macOS the container's clock drifts
behind the host's under load, so the request instant exceeded the database's idea of now and an entirely ordinary
cancellation was refused. The lifecycle guard's STOP branch had already been given the matching bound earlier in
this review round; the attempt guard had not. It is now bounded by the lease it is ending, which is the honest
ceiling: fencing early is exactly what cancellation does.

Three of these were found by reviewers and the fourth by the tests. The pattern is worth naming for the next
slice: any instant that originates on the application clock and is written under a guard needs that guard's bound
to be `greatest(clock_timestamp(), ...the terms the clamp can produce)`, and a bare `clock_timestamp()` upper
bound is a defect every time.

## 25. Residual risks

- **The inbox is never pruned.** Retention must exceed the broker's maximum plausible redelivery window; nothing enforces that yet, and the table grows with consumption.
- **Reassignment has no writer.** Epoch 2 is permitted by the schema and produced by nothing. That is honest for this slice but means the epoch's monotonicity is currently unexercised beyond its first value.
- **A worker is the control plane itself.** There is no separate worker process, so the service-identity boundary is enforced but not yet crossed by a real network peer.
- **The queue must be recreated** in any environment that already declared it.
- **A fenced run's external cleanup is not verified.** A database terminal state does not prove a partitioned worker stopped — there is nothing to stop yet, which is exactly why this is safe today and will not be once provisioning exists.

## 26. Required next security slice

Capability issuance. Claiming deliberately grants no authority to fetch feature source or resolve secrets, and that is the decision that turns ownership into the ability to run untrusted content. It needs its own slice, its own threat model, and its own adversarial review — in particular: what a capability is bound to, how long it lives, whether it survives fencing, and how a fenced worker's capability is revoked.

## 27. Recommended next slice

`CLAIMED → PROVISIONING` with capability issuance, or a separate worker process that exercises the service-identity boundary across a real network. The first is the higher-value and higher-risk of the two; the second would make the boundary this slice built real rather than notional.
