# ADR-029: Continuous execution authority and lease-bounded revocation

**Status: ACCEPTED.** Extends the lease from a write-fencing token into a bound on how long a worker may keep
executing. Does not close ADR-022.

## Context

Every slice since ADR-021 has treated the lease as a fencing token: a stale worker cannot commit new
authoritative state, because the database refuses its writes. That property is real, it is tested, and it is
not sufficient for hostile code.

It says nothing about whether the stale worker is still **running** anything.

The previous heartbeat made this explicit. It renewed the lease on a timer and swallowed every outcome, on the
stated grounds that authority is re-decided at the next phase transition. For state, that reasoning is
correct. For execution, it meant a workload already inside a sandbox continued after cancellation, after
fencing, and after lease expiry — until it finished on its own, hit the sandbox deadline, or the worker
happened to attempt a transition. A cancelled run kept consuming compute; the user who cancelled it had no way
to tell.

## Decision

### The lease bounds execution, not only writes

Two separate properties, both required:

1. A stale worker cannot mutate authoritative state. *(Already true. Unchanged.)*
2. A stale worker cannot continue executing a workload indefinitely. *(This ADR.)*

### The heartbeat returns a decision, not a boolean

The control plane already computed a precise reason for every renewal —
`RENEWED`, `RUN_NOT_OWNED`, `STALE_ASSIGNMENT`, `LEASE_EXPIRED`, `NO_ACTIVE_ASSIGNMENT`,
`CLOCK_NOT_ADVANCED` — and the controller discarded all of it, answering 204 or 409 with an empty body.

That was not merely lossy. It made the previous design's conclusion correct: a worker that cannot tell "you
have been fenced" from "the database clock stepped backwards" cannot safely act on either, so it should not
act at all. The narrow fix is to stop throwing away what was already known.

The response also carries the lease window as two instants — `serverNow` and `leaseExpiresAt` — **both read in
the database's clock**.

### Definitive loss stops execution; transient failure consumes budget

Three materially different things used to arrive as one "the heartbeat failed": unreachable, server error, and
an authoritative refusal. Collapsing them leaves only two possible behaviours and both are wrong — kill on any
failure and a dropped packet ends a healthy run; survive any failure and a fenced worker keeps executing.

`CLOCK_NOT_ADVANCED` is deliberately **not** definitive. Its causes are a backwards NTP step or a failover to a
standby whose clock trails; the assignment is untouched and the lease still runs. An unrecognised decision
*is* definitive, which is the opposite of how the unknown is usually treated here: a decision this worker
cannot interpret means the control plane is newer than the worker, and that is precisely where failing closed
costs a run and failing open costs the boundary.

### The budget is monotonic, and the conversion happens once

The runner never compares its wall clock against a database instant. It takes the **difference** of the two
returned instants — a duration, computed inside one clock domain — and converts it to a deadline on
`System.nanoTime`. Two hosts' wall clocks differ by whatever NTP has not corrected; a duration does not.

A safety margin of one heartbeat interval plus one renewal timeout is subtracted, so the worker stops
*before* its authority can have expired. The margin is derived rather than chosen: the worker must be able to
complete one renewal attempt inside it, or the deadline could fall in the middle of the request that would
have extended the lease.

### One renewal at a time, and bounded

The general control-plane call retries three times with backoff and can take around ninety seconds — longer
than a thirty-second lease. A renewal that can block for longer than the lease it renews is not a renewal
mechanism, so renewals use a single bounded attempt and the monitor does its own retrying on the interval.

Renewals never overlap: the request runs on the monitor thread and the wait happens after it returns. At a
fixed *rate*, a slow control plane collects a growing pile of concurrent renewals from a worker whose only
problem is that the server is busy.

### The sandbox is interrupted for real

A sentinel thread watches the authority while the execution thread waits for the container. On loss it stops
the container — gracefully, then forcibly — and the blocking wait returns because the container died. Nothing
depends on the workload cooperating.

The obvious alternative was tried and abandoned on evidence: awaiting the exit in slices does not work, because
docker-java's `awaitCompletion(timeout, unit)` closes the stream on timeout. Measured against a sixty-second
sleep, the first slice returned false at 257ms and the second returned *true* at 261ms with the container still
running — so a sliced wait would have reported every long workload as finished a quarter-second after it
started.

### Nothing is submitted after authority ends

Checked before any other reading of the outcome. A stopped workload may have produced a well-formed result a
moment earlier, and that result is not evidence about anything — it was produced by a worker that no longer
had the right to produce it. The worker reports nothing: whatever revoked the authority already knows what the
run is, and a stale worker adding to that is exactly the write fencing exists to refuse.

### The old mechanism is deleted, not left dormant

`LeaseHeartbeat` is removed rather than kept alongside. A weaker path that still compiles is a weaker path
something will eventually use.

## Consequences

- Cancellation is observable by a running worker, and stops it. Measured end to end: an hour-long workload
  terminated **10.2 seconds** after the user cancelled.
- A prolonged control-plane outage terminates the workload fail-closed rather than continuing on last known
  good.
- A transient outage inside the budget does not stop a healthy run, and the run completes normally.
- Under the mediating runtime the sentry is terminated with the container, not left behind.
- Every sandbox this repository starts — probes included — goes through the interruption path, because the
  uninterruptible entry point was removed rather than kept for the trusted suites.

## Residual risks

- **Revocation is not instantaneous.** It is bounded by one heartbeat interval plus one graceful stop window,
  roughly ten seconds. Hostile code has that long.
- **A hard crash still relies on the reconciler.** A `SIGKILL`ed runner runs no cleanup; the lease expires and
  the orphan reconciler removes what is left. Unchanged, and not claimed otherwise.
- **The budget assumes the returned window is honest.** It comes from the control plane over an authenticated
  internal channel; a compromised control plane could extend it, and that is the same trust already required
  to authorize the execution at all.
- **The safety margin is derived from this deployment's timings.** A materially longer renewal timeout or a
  shorter lease would need it re-derived, not merely re-tuned.

## What this does not change

**ADR-022 remains open.** Bounding how long a stale worker may execute is a prerequisite for hostile tenant
code, not a grant of permission to run it. Tenant execution stays unavailable: no feature source, no tenant
secrets, no production secret provider, no Karate. And ADR-028's rule is untouched — an authority-monitor
failure never becomes a reason to fall back to the baseline runtime.
