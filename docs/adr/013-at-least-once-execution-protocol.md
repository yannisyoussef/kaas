# ADR-013: At-least-once execution protocol with outbox, inbox, and fencing

## Status

PROPOSED

## Context

Database commits, broker publication, worker claims, result processing, and artifact registration cannot be one distributed transaction. Crashes can occur before or after acknowledgment, and brokers may redeliver or reorder. Claiming exactly once would hide rather than solve these cases. No broker adapter or persistence tables exist.

## Decision

Assume at-least-once delivery. Atomically write run mutations and immutable outbox records; acknowledge consumed messages only after inbox, domain effects, and outgoing outbox records commit. Deduplicate exact message IDs and payload digests. Bind every command, result, and manifest to trusted organization/project/run/attempt identity plus assignment epoch. Use compare-and-set run versions and reconciliation over authoritative deadlines/leases rather than broker ordering.

Keep command, lifecycle event, execution result, artifact manifest, and public live-event contracts distinct and transport-neutral. Apply bounded transient retry and route permanent validation/version/poison/exhausted failures to a conceptual DLQ. Infrastructure retry is disabled for the MVP and would create a new attempt.

## Alternatives considered

- Database update followed directly by broker publish.
- Broker transaction or acknowledgment as the system of record.
- “Exactly-once” execution through message deduplication alone.
- Global run locks and broker ordering.

## Why alternatives were rejected

Dual writes lose either state or messages on crash. Broker state cannot enforce control-plane invariants. Deduplication prevents repeated effects but cannot prove a sandbox executed once. Locks/order add coupling and fail under partitions or redelivery.

## Consequences

### Positive

- Crash windows and duplicate behavior are explicit and testable.
- Domain contracts remain independent of RabbitMQ topology.
- Stale workers and conflicting evidence are fenced/quarantined.

### Negative

- Requires outbox/inbox persistence, cleanup, reconciliation, and DLQ operations.
- Physical execution may still occur more than once; downstream test targets must tolerate that risk.

### Neutral

- Broker topology is deferred until an adapter is implemented.

## Validation and revisit conditions

Promote only after crash-injection integration tests cover every commit/ack boundary, republish, redelivery, poison handling, stale epoch, conflicting digest, cancellation/result race, lease loss, and reconciliation. Revisit transport choice only with measured operational requirements; never weaken at-least-once assumptions.
