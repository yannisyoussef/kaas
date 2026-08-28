# ADR-009: Separate run lifecycle, cancellation, test, infrastructure, and quality outcomes

## Status

PROPOSED

## Context

One `status` cannot safely distinguish orchestration progress, assertion evidence, platform reliability, user cancellation, and policy evaluation. Duplicate delivery and cancellation/result races require explicit ownership and optimistic concurrency. No run aggregate or persistence exists.

## Decision

Use one monotonic lifecycle ending only in `COMPLETED`, plus orthogonal cancellation status, test outcome, infrastructure outcome, and separately versioned quality-gate evaluation. Add explicit collecting, processing, and stopping phases. Apply every durable transition with compare-and-set on `runVersion`; bind worker ownership to attempt identity and a monotonic assignment epoch. Commit the transition and event outbox atomically.

For the MVP, permit one infrastructure attempt and no automatic infrastructure retry. Non-success infrastructure makes test outcome unavailable. The first valid committed compare-and-set wins cancellation, result, and timeout races. Broker order and worker timestamps never decide the winner.

## Alternatives considered

- One enum containing `RUNNING`, `PASSED`, `TEST_FAILED`, `TIMED_OUT`, and `CANCELLED`.
- Terminal lifecycle states for every outcome.
- Pessimistic/distributed locks around transitions.
- Automatic retry on assertion or infrastructure failure.

## Why alternatives were rejected

A combined enum cannot represent “tests passed but gate failed” or “no result because runner failed” without semantic loss. Duplicating outcomes as terminal states creates contradictory combinations. Distributed locks add failure modes and do not remove duplicate/stale messages. Automatic retries change test meaning and can repeat side effects before policy is explicit.

## Consequences

### Positive

- Every terminal combination has unambiguous evidence semantics.
- Cancellation, timeout, worker loss, and stale results have deterministic race rules.
- Optimistic concurrency and fencing work with at-least-once delivery.

### Negative

- More fields and transition tests than a single status enum.
- A future infrastructure retry policy must add attempt-selection semantics deliberately.

### Neutral

- Quality evaluation can be recalculated without rewriting execution evidence.

## Validation and revisit conditions

Promote only after table-driven domain tests cover every valid/invalid edge, expected version, duplicate, cancellation race, deadline, lease expiry, and terminal immutability. Revisit outcome compatibility only with explicit product evidence and a contract migration plan.
