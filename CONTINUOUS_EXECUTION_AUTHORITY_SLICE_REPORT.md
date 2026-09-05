# Continuous execution authority slice report

**Scope:** KAAS-16. A lease that bounds how long a worker may keep *executing*, not only what it may write.

**Starting commit:** `a9059d68`, eight-of-eight green.

**What it does not do:** it does not make tenant execution available, and it does not close ADR-022.

---

## 1. Executive summary

A workload already inside a sandbox used to keep running after its authority ended — after cancellation, after
fencing, after lease expiry — until it finished on its own, hit the sandbox deadline, or the worker happened to
attempt a phase transition. Database fencing stopped it *writing*; nothing stopped it *running*.

Now the worker continuously maintains evidence that it still owns the assignment. A definitive refusal stops
the sandbox promptly; an unreachable control plane consumes the remaining lease budget and then stops it
fail-closed; a transient outage inside the budget does not disturb a healthy run.

Measured end to end against a real control plane: **an hour-long workload terminated 10.2 seconds after the
user cancelled.**

## 2. Starting state

`a9059d68`, clean tree, eight required checks green. Verified with `git status --short --branch` before
anything was changed, as the prompt required.

## 3. The heartbeat as it actually was

Read before designing, and it matched the brief exactly. `LeaseHeartbeat` renewed on a fixed *rate*, caught
both `RuntimeException` and `ControlPlaneUnavailable` and swallowed them, and documented that a failed
heartbeat deliberately does not stop execution because authority is re-decided at the next phase advance.

Its reasoning was sound for state and wrong for execution.

## 4. Threat model

The workload is trusted today and will not be. What changes with hostile content is that the compute itself is
the asset being abused: a cancelled run that keeps executing is a tenant consuming resources they no longer
have authority for, and — once the sandbox holds hostile code — time in which that code keeps running while
the platform believes it has stopped it.

## 5. Why database fencing was insufficient

```
a fenced worker whose every write is rejected
        is still burning CPU inside a sandbox
        until something stops the sandbox
```

Two separate claims. Every existing fencing test proves the first. None of them touches the second.

## 6. The authority decision model

Eight decisions, each marked definitive or not. The distinction is the whole design: collapse it and only two
behaviours remain, both wrong — kill on any failure and a dropped packet ends a healthy run; survive any
failure and a fenced worker keeps executing.

`RENEWED`, `RUN_NOT_OWNED`, `STALE_ASSIGNMENT`, `LEASE_EXPIRED`, `NO_ACTIVE_ASSIGNMENT`, `CLOCK_NOT_ADVANCED`,
`UNAVAILABLE`, `UNRECOGNIZED`. The vocabulary is closed; an unrecognised reason maps to `UNRECOGNIZED` rather
than throwing, because an exception on the monitor thread would end the monitor.

## 7. `CLOCK_NOT_ADVANCED` is a refusal that is not about authority

Its causes are a backwards NTP step, a failover to a standby whose clock trails, or two renewals inside one
microsecond. The assignment is untouched and the lease still runs. Treating every refusal as fencing would end
healthy executions for a reason that has nothing to do with ownership, so it is handled exactly like an
unreachable control plane.

## 8. `UNRECOGNIZED` fails closed, which is deliberate asymmetry

Everywhere else in this repository an unknown answer is treated conservatively toward *not* acting. Here it
stops execution, because a decision this worker cannot interpret means the control plane is newer than the
worker — and that is precisely the case where failing open costs the boundary.

## 9. The protocol change was to stop discarding what was already computed

`WorkerLeaseService` already produced a precise reason for every renewal. The controller collapsed all of it
into 204 or 409 with an **empty body**.

That was not merely lossy: it made the old design's conclusion correct. A worker that cannot tell fencing from
a clock step cannot act on either. The change is narrow — return the decision, plus the lease window.

## 10. The lease window, and the clock domain rule

`serverNow` and `leaseExpiresAt`, both read in the **database's** clock, returned as a pair. The runner takes
their difference — a duration computed inside one clock domain — and never compares its own wall clock against
a database instant. Two hosts differ by whatever NTP has not corrected; a duration does not.

`CLOCK_NOT_ADVANCED` and `LEASE_EXPIRED` carry the window too. For the first the lease is still valid and the
worker needs to know how much is left; for the second, saying so is more useful than silence.

## 11. The monotonic budget

The window converts once into a deadline on `System.nanoTime`, behind a one-method `MonotonicClock` type.
`java.time.Clock` was deliberately not used: it is a wall clock, and using one to answer "how long have I been
unable to renew" invites a budget that a clock adjustment can silently extend.

## 12. The safety margin is derived, not chosen

`interval + renewal timeout` = 10s. The floor is set by requiring that the worker can complete one full
renewal attempt inside the margin — otherwise the deadline could fall in the middle of the very request that
would have extended the lease.

The margin errs toward stopping early. Stopping early costs a run that might have continued; stopping late
means code ran with no authority behind it.

## 13. The initial budget

`margin + interval` = 15s. The runner cannot see its lease until a renewal tells it, and beginning unbounded
would leave a window before the first renewal in which nothing bounded execution — the gap this slice closes,
reintroduced at its own start.

## 14. One renewal at a time, and bounded

The general control-plane call retries three times with backoff: up to about **ninety seconds** against a
thirty-second request timeout, which is longer than a thirty-second lease. A renewal that can block longer
than the lease it renews is not a renewal mechanism, so renewals use a single bounded attempt and the monitor
retries on its own interval.

Fixed *delay*, not fixed *rate*: at a fixed rate a slow control plane collects a growing pile of concurrent
renewals from a worker whose only problem is that the server is busy. Asserted directly.

## 15. Transient outage handling

Failures consume budget and decide nothing. If connectivity returns, execution continues. Proven end to end:
renewals failed for six seconds against a live control plane and the run still **completed with PASSED**.

## 16. Explicit fencing and cancellation

Cancellation reaches a running worker because a cancelled run leaves the owned states and the next renewal is
refused `RUN_NOT_OWNED`. It stops immediately, without waiting for the budget — there is nothing to wait for.

## 17. What the database refuses, and what that taught

Two attempts to force a stale state were refused by the schema, and both refusals are informative:

- Fencing an attempt while its run is still owned: `require_complete_scheduling_bundle` raises *"an owned run
  requires exactly one attempt holding the active assignment"*.
- Bumping an assignment epoch directly: `guard_execution_attempt` permits only claim, acquire, heartbeat,
  fence and execution-history transitions.

So for a **running** worker the reachable definitive decisions are `RUN_NOT_OWNED` and `LEASE_EXPIRED`.
`STALE_ASSIGNMENT` is refused at authorization, before a sandbox exists. The tests were changed to match
reality rather than forcing a state the database will not hold.

## 18. The authority monitor

One thread, one assignment, at most one renewal in flight, a single terminal reason written once with
`compareAndSet`, and a deadline in an `AtomicLong`. Not a scheduler and not a retry framework.

Closing does **not** set a reason: ordinary completion is not revocation, and recording one would make every
successful execution end by reporting that its authority was taken away.

## 19. The sandbox interruption model, and the design that failed

The execution thread blocks on the container exactly as before; a **sentinel** thread watches the authority
and terminates the container when it ends, so the blocking wait returns because the container died.

The obvious alternative — awaiting in slices, re-reading the authority between them — was implemented first
and is silently broken. docker-java's `awaitCompletion(timeout, unit)` **closes the stream on timeout**.
Measured against a container sleeping sixty seconds:

```
slice 0: done=false at 257ms
slice 1: done=true  at 261ms      <- container still running
```

A sliced wait would have reported every long workload as finished a quarter-second after it started.

## 20. Graceful then forced termination

Docker's own stop implements the graceful bound — signal, wait, kill — followed by an explicit kill, because
"the stop command returned" is not the same claim as "nothing is running". Hostile code that ignores the
signal reaches the forced kill; the stopping time is the platform's property, not the workload's.

## 21. gVisor termination

Asserted in the mandatory gate on a host that actually has the runtime: the container is gone **and** the
runtime process is gone. A container vanishing from `docker ps` is not evidence about the sentry, and a sentry
outliving its container is a host process still holding the workload.

## 22. `DENY_ALL`

No proxy exists, so there is no network-side control that could otherwise cut the workload's capability.
Termination is the only mechanism, which is why the baseline revocation tests use it.

## 23. `ALLOWLIST`

Proven at the egress layer, where a long-running egress workload can actually be produced: the sandbox stops,
the proxy stops, and the per-execution network disappears — asserted separately, because proxy closure alone
leaves a sandbox running code with no network, which is not the claim.

## 24. Active tunnel coordination

ADR-026's revalidation still closes established tunnels within its bound. It is useful and it is **not**
workload termination. All layers converge on the same revoked authority.

## 25. Phase coverage

The monitor wraps `execute(...)`, which contains every worker-owned phase. Verified behaviourally rather than
lexically: the provisioning check is asserted by its own test, and revocation during `RUNNING` is asserted end
to end.

## 26. No stale success

Checked before any other reading of the outcome. A stopped workload may have produced a well-formed result
moments earlier, and that result was produced by a worker that no longer had the right to produce it. Nothing
is submitted; whatever revoked the authority already knows what the run is.

Both axes are covered: the worker declines to submit, **and** the database would refuse it.

## 27. Runner shutdown

Monitor and sentinel are daemon threads, so a shutting-down runner is never held open. `close()` interrupts
rather than waiting for the sleep to expire — asserted, because a close that waited would take a full
heartbeat interval on every completed execution.

## 28. Crash recovery

Unchanged and not overclaimed. A `SIGKILL`ed runner runs no cleanup; the lease expires, the reconciler fences,
and the orphan reconciler removes the sandbox, proxy and network.

## 29. Persistence

**No migration.** The heartbeat response is derived from state the database already holds; local monitor state
is local. The prompt asked whether persistence was actually needed, and it is not.

## 30. Race testing

Loser outcomes are captured rather than discarded — every revocation test records what its worker thread threw
and asserts it was nothing. The natural-finish race is decided by whether authoritative result acceptance won,
not by which thread ran first.

## 31. Security review findings

- **A dead monitor abandoned its workload.** An unexpected throw ended the thread silently, leaving the
  terminal reason null and the budget frozen, so the sandbox ran to its own deadline with nothing watching it.
  Now an unexpected throw ends execution.
- **The renewal path could block longer than the lease.** Fixed by a single bounded attempt.
- **`LeaseHeartbeat` was dead code after the change.** Deleted rather than left dormant.

## 32. Mutation evidence: 19 run, 19 killed

| # | Mutation | Result |
| --- | --- | --- |
| A1 | authority loss signal ignored | killed |
| A2 | explicit refusal treated as transient | killed |
| A3 | refused renewal extends the budget | **survived ×2** → deterministic budget assertion → killed |
| A4 | pre-sleep expiry check removed | **survived** → dead guard removed → killed |
| A5 | safety margin removed | killed |
| A6 | monitor stops after first transient error | killed |
| A7 | renewals allowed to overlap | killed |
| A8 | wall clock used for the budget | killed |
| A9 | forced kill removed | **survived** → joint coverage (§33) |
| A10 | sandbox termination callback removed | killed |
| A11 | authority not checked before start | killed |
| A12 | result reported after authority lost | killed |
| A13 | outcome does not name the authority loss | killed |
| A14 | heartbeat decision discarded by the controller | killed |
| A15/A16 | workload launched without the authority | **survived ×2** → §34 → killed |
| A17 | monitor never renews | killed |
| A18 | monitor leaks on close | **survived** → prompt-close assertion → killed |
| A19 | dead monitor abandons the workload | killed |

## 33. Joint coverage

`terminate()` is a Docker stop with a timeout followed by an explicit kill. Docker's stop already SIGKILLs
after its timeout, so removing the kill still terminates. The kill is not redundant in one case — when the
stop command itself throws — and that case cannot be produced against a real daemon here. Recorded as joint
coverage; the kill is not deleted to make the mutant observable.

## 34. The most instructive survivor

Mutating the launch to pass a *retained* authority survived twice. The loop's own check still returned
`AUTHORITY_LOST` and the abandoned sandbox merely ran to its 30-second profile deadline. **The report was
right and the workload was not terminated** — the two axes, in one line of code.

```
clean:   stoppedIn = PT10.16S
mutated: stoppedIn = PT30.07S
```

Two fixes: one call site, so the allowlist and deny-all paths cannot drift apart; and the bound tightened from
45s to **18s**, which is the only thing separating "terminated" from "ran out its own deadline".

## 35. Two harness errors of my own

Recorded because they invalidated results before they were caught:

1. A mutation run without `cleanTest` reported SURVIVED when the tests had simply not re-executed.
2. Two edits silently did not apply — an earlier rewrap had re-indented the anchor and the script did not
   assert on the replacement — so a bound I believed I had tightened was never tightened through two retests.

## 36. A vacuous check inherited from the previous slice

`pgrep -c runsc` **never matches the sentry**: gVisor re-executes itself, so the process name is `exe` with
`runsc-sandbox` in the arguments. The leak check added in KAAS-15 has therefore always returned zero
regardless of what was running.

The first correction over-corrected: grepping the whole argument list matched the checking script's own
command line and could only ever return one. The check now matches the process's **first token**, so its
answer depends on whether anything leaked. Both wrong versions are recorded in the file, because the shape of
the mistake matters more than the fix.

## 37. QE evidence

| Property | Where | Measured |
| --- | --- | --- |
| Budget arithmetic, all cases | unit, fake monotonic clock | 12 tests, 0.5s total |
| Sandbox terminated mid-execution | runner suite | 5.4s against a 3600s sleep |
| Cancellation, end to end | pipeline | 10.2s |
| Transient outage survived | pipeline | run COMPLETED / PASSED |
| Prolonged outage, fail-closed | pipeline | 10.3s, no result |
| Egress: sandbox + proxy + network | egress gate | 7.1s |
| Mediated runtime + sentry gone | strong-runtime gate | CI only — `runsc_processes=0` after |

## 38. CI evidence

**Final run 33994573630 at `ab7720aa`: eight of eight green.**

```
backend · hostile-execution-gate · synthetic-execution-pipeline · execution-egress-gate
strong-runtime-gate · web · contracts · infrastructure
```

From the gate's own inspection steps:

```
executed=7  skipped=0
containers=0  networks=0  runsc_processes=0
mediated attestation: kaas.sandbox.gvisor.v1 GVISOR 17 controls   verification=VALID
```

The leak line is the one worth reading twice. It says zero because the check now counts the runtime's own
executables; the two earlier versions of that line would have said zero and one respectively, whatever was
running.

No ninth check. `StrongRuntimeAuthorityRevocationTests` was added to the existing `strong-runtime-gate`, named
explicitly rather than by a glob so a renamed class cannot silently drop out of a mandatory gate. The gate's
evidence step now requires **both** suites by name and at least seven executed tests with zero skips.

## 39. Required-check governance

Unchanged at eight. The prompt's preference for extending an existing gate over creating a ninth was followed.

## 40. Residual risks

- **Revocation is not instantaneous** — bounded by one interval plus one graceful window, about ten seconds.
- **A hard crash still relies on the reconciler.**
- **The budget trusts the returned window**, which requires the same trust already needed to authorize the
  execution at all.
- **The margin is derived from this deployment's timings** and would need re-deriving, not re-tuning, if the
  renewal timeout or lease changed materially.

## 41. Remaining blockers before tenant source

ADR-022 is not closed. Bounding a stale worker's execution is a prerequisite, not permission. Still absent:
Karate, FeatureRevision source in a sandbox, tenant secrets, a production secret provider, object storage,
SSE, quality gates.

## 42. Recommended next slice

Worker heartbeating is no longer the blocker it was. The next honest step is either the source capability
being redeemed into a sandbox — the first slice where tenant-authored bytes exist inside the boundary — or
closing ADR-022 explicitly on the evidence now available. They should not be combined.
