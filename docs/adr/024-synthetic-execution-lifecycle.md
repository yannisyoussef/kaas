# ADR-024: The synthetic execution lifecycle

## Status

IMPLEMENTED. A run executes, produces evidence, and completes. **What executes is a platform-owned synthetic
workload, not a test engine.** No feature source enters a sandbox, no secret is resolved, and no Karate exists
anywhere in this repository.

## Context

Every slice before this one stopped work before it started. ADR-021 established who owns an attempt; ADR-023
established whether that owner may execute; ADR-022 established that a sandbox confines what runs inside it.
None of them ran anything, and a run that reached `CLAIMED` stayed there permanently.

The obvious next step is to integrate a test engine. That would be a mistake, and the reason is worth stating
plainly: the execution lifecycle is where a distributed system's hardest problems live — fencing, deadlines,
partial failure, evidence, reconciliation — and none of them are engine problems. Debugging a lost result is
hard. Debugging a lost result *while also* debugging a Gherkin parser, a classloader, an HTTP client, and a
report format is a different and much worse activity, and the temptation under that load is to make the
lifecycle lenient until the engine works.

So the lifecycle is composed first, end to end, against a workload the platform wrote and fully controls.

## Decision

### Four phases, each with a bounded exit

`CLAIMED → PROVISIONING → RUNNING → COLLECTING_RESULTS → PROCESSING_RESULTS → COMPLETED`, plus `STOPPING` from
any owned phase.

Each phase carries a deadline in `test_runs.phase_deadline_at` — one column, not four. The lifecycle state
already says which phase the deadline bounds, so a column per phase would be three NULLs and a value at every
instant, three more constraints to keep them consistent, and four indexes to answer one question. A single
instant that each transition re-arms answers "what is overdue" with one partial index, and makes "this phase
has no deadline" impossible to express by accident:

```sql
CHECK ((lifecycle_state IN ('PROVISIONING','RUNNING','COLLECTING_RESULTS','PROCESSING_RESULTS'))
       = (phase_deadline_at IS NOT NULL))
```

**No state without a bounded exit.** An earlier slice shipped a state whose only exit was a worker choosing to
act, and a worker that has crashed chooses nothing. Every phase here has a success transition, a cancellation
transition, and a timeout transition before it is reachable at all. One reconciler serves all four phases,
because they differ only in which reason they record — four timers scanning one index for four disjoint
predicates would be four times the work to answer one question, and the three that fired least often would be
the three nobody noticed had stopped.

### Outcomes are orthogonal

"The infrastructure worked and the test failed" and "the infrastructure failed so there is no test result" are
different facts, and one column cannot carry both:

```sql
CHECK (infrastructure_outcome IS NULL
       OR (infrastructure_outcome = 'SUCCEEDED') = (test_outcome IN ('PASSED','FAILED')))
```

This propagates all the way down. The synthetic workload **exits zero even when its assertions fail**, because
a failing test is not a failing execution — the infrastructure did its job: it ran the workload and collected
the result. Had the exit code tracked the test outcome, every red test would have been indistinguishable from
a broken sandbox, and the people who noticed first would be the ones who stopped trusting the platform's own
failure reports.

### Results are evidence, not claims

A result document arrives over the network from a worker. On its own account it is a claim. What makes it
evidence is that the control plane independently establishes it came from the assignment currently authorized
to produce it — same run, same attempt, same epoch, answering the same issued command, over the same sealed
snapshot, starting at the instant the control plane itself stamped.

Every identity field is recorded from authoritative state. The document's own copies are compared against them
and the submission is refused on any disagreement; they are never the source. Two of those comparisons were
initially worthless and had to be fixed:

- The command identifier was compared only against the document's own copy — two fields the same caller
  supplies, which agree with each other whatever value was chosen. It is now checked against
  `execution_commands`, scoped by attempt **and** epoch.
- The run version was compared against the run's *current* version, which refuses every honest result: the run
  advances through four phases between authorization and submission, and the worker cannot know where it got
  to. It is now compared against the version the command was issued for.

Acceptance and completion are one transaction. Evidence without completion describes something the platform
still believes is running; completion without evidence is what the database's own trigger refuses.

### The guards were rewritten as a unit, four times

Adding lifecycle states invalidates every guard that enumerates them. Four had to be replaced wholesale, and
each was found by running the pipeline rather than by inspection:

| Guard | What it refused |
|---|---|
| `guard_supported_test_run_update` | every new transition |
| `guard_execution_attempt` | every write to the new execution-history columns |
| `require_complete_scheduling_bundle` | all four phases, as "not an implemented state" |
| `ck_run_lifecycle_events_transition` | the new edges |

The lesson is recorded here because it will recur: **when a slice adds lifecycle states, enumerate every
function in the migrations that references a state, before writing any code.**

`guard_execution_attempt` gained a property worth naming. The columns an assignment transition may move and the
columns an execution-history write may move are **disjoint by construction**, so no single statement can do
both. A worker reporting progress therefore cannot renew its own lease as a side effect. The price is that
ending an attempt takes two statements — one for what it did, one for the fact that it no longer owns anything
— and that price is worth paying.

### The engine is named honestly

`EngineDescriptor` was hardcoded to `KARATE`, and so was the database CHECK behind it. No Karate exists in this
repository, so every snapshot and every command declared an engine that could not have produced their results.
While nothing executed, that was merely wrong. The moment a runner executed, it would have meant three shell
assertions reported to every dashboard, every result consumer, and every operator as a passing Karate suite.

The engine is now configured, defaults to `SYNTHETIC`, and the runner **refuses to execute any engine it does
not have** — so a misconfiguration fails closed instead of quietly running synthetic logic under another name.
`KARATE` remains a valid value so the model can be built toward it. This is the same shape as the network
policy: represented, unenforceable here, refused rather than silently degraded.

## Consequences

**A run can now finish.** `EXECUTION_COMPLETED` is the first termination reason in this platform's history that
means "finished normally" — until now every terminal run had failed, been cancelled, or timed out.

**Cancellation reaches the execution phases.** Previously only `CLAIMED` was cancellable. A phase a tenant
cannot stop is a phase where a stop request silently does nothing until a deadline expires, and the longest of
those is the 30-minute execution budget — exactly when someone most wants to stop. `PROCESSING_RESULTS` is
deliberately excluded from *tenant* cancellation, because by then the execution is over and cancelling would
discard evidence the platform already paid to produce; the platform can still reclaim it.

**A stopped worker is told the truth.** Both stop paths fence the assignment, so "you are fenced" is always
also true — but reporting `ASSIGNMENT_STALE` tells a worker somebody else took its run, which is false. The
refusal is now `RUN_STOPPING`. This was found by mutation testing: while the fence answered first, deleting the
stop check entirely changed no observable behaviour and no test could tell.

**The runner does not heartbeat during execution.** The lease must currently outlive the whole run. That is
fine for a seconds-long synthetic workload and **not** fine for the 30-minute `RUNNING` budget. Heartbeating is
a prerequisite for any real engine, and is not in this slice.

**Ordinary Karate runs are still not executable**, and will not be until egress is enforceable (ADR-025) and an
engine is integrated. That is the correct order: lifecycle first, egress next, engine last.
