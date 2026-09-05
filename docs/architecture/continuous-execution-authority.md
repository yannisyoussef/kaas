# Continuous execution authority

**Status: IMPLEMENTED and VALIDATED.** See [ADR-029](../adr/029-continuous-execution-authority.md) for the
decision and `CONTINUOUS_EXECUTION_AUTHORITY_SLICE_REPORT.md` for the evidence.

## The gap this closes

Database fencing already stops a stale worker **writing**. It says nothing about whether that worker is still
**running** anything.

Before this, a workload already inside a sandbox kept executing after cancellation, after its assignment was
fenced, and after its lease expired — until it finished on its own, hit the sandbox's own deadline, or the
worker attempted a phase transition and was refused. For a workload this repository wrote that is wasteful.
For hostile tenant code it is the whole problem.

```
STATE FENCING                          EXECUTION FENCING
the database refuses the write         the runner stops the computation
        |                                        |
        v                                        v
"your result is not accepted"          "your sandbox is gone"

  already existed                        added by ADR-029
```

Both are required. Neither substitutes for the other.

## The shape

```
                    control plane
                          |
                          |  renewal request, one at a time, bounded
                          v
                 +------------------+
                 | authority monitor |
                 +------------------+
                          |
        +-----------------+--------------------+
        |                 |                    |
     RENEWED        explicit refusal        unavailable
        |                 |                    |
        v                 v                    v
  budget reset       stop NOW,           consume the remaining
  from the lease     do not wait         lease budget
  window                 |                    |
        |                |            recovered before it runs out?
        v                |                 /            \
  sandbox continues      |              yes              no
                         |               |                |
                         +---------------+                v
                                         |          stop the sandbox
                                         v
                                  sandbox continues
```

## The clock rule

The runner never compares its own wall clock against a database instant. Two hosts differ by whatever NTP has
not corrected yet, and that difference is invisible, varies, and can be either sign.

```
heartbeat response:  serverNow ──┐
                                 ├── both read in the DATABASE's clock
                     leaseExpiresAt ─┘
                          |
                          v
        window = leaseExpiresAt − serverNow      (a DURATION, computed inside one clock domain)
                          |
                          v
        deadline = MONOTONIC_NOW + window − safetyMargin
                          |
                          v
        from here on, only System.nanoTime decides
```

A duration means the same thing on both hosts. An instant does not.

## The numbers, and where they come from

| Value | Setting | Why |
| --- | --- | --- |
| Heartbeat interval | 5s | The resolution of revocation: nothing can be noticed sooner than the next renewal. |
| Renewal timeout | 5s | One attempt, bounded. The ordinary retrying client call can take ~90s — longer than a 30s lease, which makes it useless as a renewal. |
| Safety margin | interval + renewal timeout = 10s | **Derived, not chosen.** The worker must be able to make one complete renewal attempt inside the margin, or the deadline could fall in the middle of the request that would have extended it. |
| Initial budget | margin + interval = 15s | The runner cannot see its lease until a renewal tells it. Assuming a generous one would be assuming authority it has not been given. |
| Graceful stop | 5s | Then a forced kill. Hostile code that ignores the signal does not get to choose how long it keeps running. |

The margin errs toward stopping **early**. Stopping early costs a run that might have continued; stopping late
means code ran with no authority behind it.

## Definitive versus transient

This distinction is the whole design. Collapsing it leaves only two possible behaviours, and both are wrong:
kill on any failure and one dropped packet ends a healthy run; survive any failure — which is what the runner
did — and a fenced worker keeps executing.

| Decision | Definitive? | What it means |
| --- | --- | --- |
| `RENEWED` | no | The lease was extended. Budget reset. |
| `RUN_NOT_OWNED` | **yes** | Cancelled, stopped, or terminal. This is how user cancellation reaches a running workload. |
| `STALE_ASSIGNMENT` | **yes** | Fenced, superseded, or never acquired. |
| `LEASE_EXPIRED` | **yes** | Authority is gone, not merely unrenewed. |
| `NO_ACTIVE_ASSIGNMENT` | **yes** | No assignment exists for this run. |
| `CLOCK_NOT_ADVANCED` | no | A refusal that is **not** about ownership: an NTP step back, a standby failover. The lease is untouched. Treating it as fencing would end healthy runs. |
| `UNAVAILABLE` | no | Decides nothing. Consumes budget. |
| `UNRECOGNIZED` | **yes** | An answer this build cannot read means the control plane is newer than the worker. Fails closed. |

## How a running sandbox is actually stopped

The execution thread blocks on the container's exit as it always did. A **sentinel** thread watches the
authority alongside it and terminates the container when authority ends; the blocking wait then returns
because the thing it was waiting for died.

The obvious alternative — awaiting the exit in short slices and re-reading the authority between them — does
not work, and fails silently. docker-java's `awaitCompletion(timeout, unit)` **closes the stream when it times
out**, so the first expired slice destroys the wait and every later call reports completion immediately.
Measured against a container sleeping for sixty seconds: slice one returned false at 257ms, slice two returned
*true* at 261ms with the container still running.

The interruption is real rather than cooperative. Nothing depends on the workload noticing anything.

## What converges on a revoked authority

| Layer | What happens |
| --- | --- |
| Sandbox | Stopped gracefully, then killed. Under the mediating runtime the sentry goes with it. |
| Egress proxy | Stopped with the execution. |
| Execution network | Removed with the execution. |
| Active tunnels | Already closed within their revalidation bound (ADR-026) — useful, and **not** a substitute: a sandbox with no network is still a sandbox running code. |
| Result | Never submitted. Whatever revoked the authority already knows what the run is. |

## What still relies on the reconciler

A `SIGKILL`ed runner runs no cleanup. That is expected and unchanged: the lease expires, the reconciler fences
the assignment, and the orphan reconciler removes the sandbox, the proxy and the network. Nothing here claims
synchronous cleanup after a hard crash.
