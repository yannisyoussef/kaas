# The synthetic execution lifecycle

**Status: IMPLEMENTED end to end. What executes is a platform-owned synthetic workload, not a test engine.**

A run now starts, runs, produces evidence, and finishes. Everything that composes that lifecycle is real — the
phases, the deadlines, the fencing, the provenance checks, the reconciler. Only the workload is synthetic, and
it is named as such everywhere it appears.

## The path

```
                         TestRun CLAIMED
                               |
                     authority revalidated  ── refused ──▶  the run is abandoned
                    (assignment + epoch +                    (never retried: a refusal is
                     lease + gate + policy)                   authoritative, and retrying
                               |                              only burns the deadline)
                               v
                   command validated independently  ── refused ──▶  abandoned
                (digest recomputed from the PARSED
                 document; unknown fields fatal;
                 ALLOWLIST and KARATE refused)
                               |
                               v
    ┌─────────── PROVISIONING ──── 2m ───┐
    │                  |                 │
    │                  v                 │
    │            RUNNING ──── 30m ───────┤   any phase overdue
    │                  |                 ├──────────────────────▶  STOPPING ──▶ COMPLETED
    │                  v                 │   or cancelled            (fenced)   (no test outcome)
    │       COLLECTING_RESULTS ── 2m ────┤
    │                  |                 │
    │                  v                 │
    │      PROCESSING_RESULTS ─── 2m ────┘
    │                  |
    │                  v
    └────────▶  result submitted, provenance checked
                       |
                       v
                   COMPLETED
              (the only path that
               carries a test outcome)
```

**Every phase is announced before the work it names begins.** A runner that dies mid-phase therefore leaves a
run whose recorded state is at worst one step ahead of reality — which a reconciler can act on. Announcing
afterwards would leave a window in which a container is running and no deadline covers it, and a run recorded
as `CLAIMED` with a live container behind it is an orphan nobody is looking for.

## What the workload actually is

`KAAS_SYNTHETIC_V1`. Three deterministic shell assertions in the trusted probe image, plus an optional
deliberate failure so the `FAILED` terminal outcome is reachable in a test — without it, that transition would
ship having never once executed, and the first genuine test failure in production would be the first time that
code ran.

It reports its own identity, and the result document says `producer: kaas-runner-synthetic`. A consumer that
cannot tell a synthetic execution from a real one will eventually treat one as the other, and the direction
that matters is a green synthetic run being read as a passing test suite.

**Zero tenant features ran, and the result says zero.** The `features` array and the summary counts describe the
tenant's suite, and none of it entered the sandbox. Populating them with the workload's own assertions would
attribute results to features that never executed — the same lie as misreporting the engine, further down the
document.

## Why the two digest implementations are duplicated on purpose

The control plane digests a command; the runner recomputes it. If the runner verified that by calling the
control plane's implementation, the two would agree by construction and the comparison would prove nothing — a
bug in the shared code would be invisible precisely *because* both sides had it. The runner's module cannot
depend on the control plane, so the independence is structural rather than a convention someone maintains.

The runner digests the document it **parsed**, not the bytes it received. Digesting bytes verifies the
transport and nothing else: a field the parser silently dropped or coerced would still be covered by a digest
over bytes, and the runner would then act on a value different from the one it verified. Verifying the
projection you are going to use is the only version of this check that means anything.

## Provenance: what makes a result evidence

| Field | Compared against |
|---|---|
| run, attempt, epoch | the locked run and its current attempt |
| command | `execution_commands`, scoped by attempt **and** epoch |
| run version | the version the **command** was issued for, not the run's current one |
| snapshot | the run's own sealed `snapshot_sha256` |
| started at | the instant the control plane stamped when the run entered `RUNNING` |
| finished at | must not precede started at |

The run-version row is the subtle one. The run advances through four phases between authorization and
submission, and the worker has no way to know where it got to — comparing against the live value refuses every
honest result. The command's version is what the worker was actually told.

The started-at row is why the phase endpoint returns `executionStartedAt`: the runner echoes the control
plane's instant rather than measuring its own, because two clocks disagree by however far the hosts have
drifted, and the check requires exact equality.

## Failure modes and what happens

| What breaks | What the platform does |
|---|---|
| worker crashes mid-phase | the phase deadline expires; the reconciler stops the run and fences the assignment |
| worker keeps running but the run is cancelled | the next phase advance is refused with `RUN_STOPPING` |
| worker's lease lapses without being fenced | refused with `LEASE_EXPIRED` — the fence flag alone would let it continue for as long as the reconciler was behind |
| a superseded worker submits a result | refused: identity and epoch are checked together |
| the sandbox will not start | `INFRASTRUCTURE_FAILURE`; the run stops with no test outcome |
| a result arrives twice | the second is refused; `UNIQUE (attempt_id, assignment_epoch)` makes it structural |
| the runner dies with a container alive | the orphan reconciler reclaims it by **age**, never by generation |
| the control plane is unreachable | transport is retried three times with exponential backoff; a 409 is never retried |

## Honest limitations

- **No heartbeating during execution.** The lease must outlive the whole run. Fine for a seconds-long synthetic
  workload; not fine for the 30-minute `RUNNING` budget. This is a prerequisite for any real engine.
- **No egress.** `DENY_ALL` only. See [ADR-025](../adr/025-execution-egress-remains-deny-all.md).
- **No feature source execution.** The bundle is described in the command and fetched by nothing; the runner's
  client has no method that could fetch it, and `ValidatedCommand` carries no field that could hold content.
- **No secrets.** A command binding any is refused by the runner outright.
- **No artifacts.** Nothing is retained and there is no object store to retain it in.
- **One attempt per run.** Infrastructure retry would create a second attempt and does not exist.
