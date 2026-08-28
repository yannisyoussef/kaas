# Messaging Reliability

**Status: PROPOSED SEMANTICS.** No broker topology, outbox, inbox, publisher, consumer, retry queue, DLQ, or reconciliation job is implemented.

## Delivery guarantee

KaaS assumes **at-least-once delivery**. It does not claim exactly-once delivery or exactly-once execution. Correctness comes from transactional state changes, stable message identities, inbox deduplication, optimistic concurrency, attempt/epoch fencing, and reconciliation.

Broker ordering is not a correctness dependency. A message is accepted only when its run state/version, command, attempt, and assignment epoch match authoritative persisted state.

## Idempotency boundaries

| Boundary | Key and conceptual storage | Exact duplicate | Conflict |
|---|---|---|---|
| API run creation | Scoped `(organization, principal, operation, project, Idempotency-Key)` plus normalized request fingerprint and stored response; implemented keys currently have no automatic expiry | Return original HTTP status/body/Location/ETag | Same scope/key with different fingerprint → 409 `IDEMPOTENCY_CONFLICT` |
| Command publication | Transactional outbox row/message ID and immutable payload digest | Republish same message ID/bytes until broker confirms | Same outbox/message ID with different digest → stop publication and security alert |
| Command consumption | Consumer inbox uniqueness on consumer identity + `messageId`; command ID/digest checked against assignment | ACK after previously committed effect | Same ID with different digest or wrong tenant/attempt/epoch → quarantine/DLQ |
| Execution claim | Attempt ID + `assignmentEpoch` and active-claim invariant | Return existing lease to same assignment | Competing worker/epoch → reject; stale epoch cannot renew |
| Result processing | `resultId`, message ID, payload digest, command/run/attempt/epoch binding | ACK and return existing canonical/quarantined disposition | Conflicting digest or second result for attempt → quarantine/security reconciliation |
| Artifact registration | `artifactId`, manifest ID, opaque reservation/reference, size and digest | No-op after verifying identical metadata | Reference reuse, metadata mismatch, wrong attempt/policy → reject/quarantine |
| Cancellation | **IMPLEMENTED** for CREATED/QUEUED: natural one-cancellation-per-run, by state. The run is the scope, so no idempotency key is required or used | Return the terminal run unchanged | Already terminal for another reason, or a phase early cancellation cannot end → 409 |

Normalization for API fingerprints includes method, canonical path, authenticated scope, media type, and canonical request JSON; it excludes transport headers such as tracing. Submitted secret values are not part of these run requests.

## Transactional outbox

The domain mutation and one or more immutable outbox records are written in the same local database transaction. No broker call occurs inside that transaction. A publisher reads committed unsent rows, publishes the exact stable message ID/body, and records broker confirmation. A crash before confirmation causes republish; a crash after broker acceptance but before marking sent also causes a duplicate. Both are expected.

Outbox solves the database/broker dual-write gap. It does not make delivery or consumer effects exactly once. Trace context may be stored alongside the outbox as bounded technical metadata solely to continue propagation.

## Consumer inbox and acknowledgment

The consumer validates transport size and an allowlisted local schema before domain effects. In one local transaction it records inbox identity/digest, performs the conditional domain mutation, and creates any outgoing outbox records. Broker acknowledgment occurs only after that transaction commits.

- Crash before commit: broker redelivers and no committed effect exists.
- Crash after commit before ACK: broker redelivers; inbox detects the exact duplicate and ACKs without repeating effects.
- Duplicate publication: same path as redelivery.
- Duplicate with changed digest: permanent integrity conflict; never treated as an ordinary duplicate.

An inbox record may retain only the digest and disposition when payload retention would create sensitive-data risk. DLQ tooling must enforce access control, retention, and redaction.

## Retries are different mechanisms

| Retry | Transparent? | New execution attempt? | MVP rule |
|---|---|---|---|
| API client retry | Yes, through idempotency | No | Same key/fingerprint replays response; exponential backoff for 429/5xx |
| Message delivery retry | Yes to the domain | No | Bounded retry only for transient transport/dependency failure |
| Infrastructure execution retry | No | Always | Disabled; future retry requires new attempt/epoch/command |
| Karate scenario retry | Visible in structured scenario attempts | No | Bounded by immutable command; assertion failures are never orchestration retries |

The orchestrator never retries a failed assertion. A user rerun creates a new run for the MVP.

## Retry and DLQ classification

| Failure | Action | DLQ / reconciliation |
|---|---|---|
| Transient network/broker/database unavailability before durable effect | NACK/retry with bounded exponential backoff and jitter | DLQ after retry budget; reconciler checks durable state |
| Permanent schema or semantic validation error | Reject without normal retry | DLQ with sanitized validation disposition; manual inspection |
| Unsupported contract major/minor not in consumer allowlist | Reject without retry | Version-specific DLQ/manual rollout reconciliation |
| Poison message causing repeatable parser/handler failure | Stop bounded retries | DLQ; payload access restricted; handler defect triaged |
| Exhausted transient retries | Stop automatic delivery retry | DLQ and alert; reconcile run/attempt deadline |
| Stale run version/attempt/epoch/deadline | ACK as stale no-op after audit | No DLQ unless pattern indicates abuse/defect |
| Identity/digest conflict or cross-tenant substitution | Reject and quarantine | Security alert and manual reconciliation; never ordinary retry |

No RabbitMQ exchange, queue, or routing-key names are chosen by this architecture. A future adapter must prevent unbounded poison loops and message amplification.

## Commands, events, results, and manifests

- A **command** requests one immutable attempt and may be redelivered.
- A **lifecycle event** states a committed control-plane fact and is written through an outbox with the state change.
- A **result** is worker evidence subject to validation; receipt is not acceptance.
- An **artifact manifest** is metadata subject to storage verification; receipt is not availability.

Consumers must route using configured transport metadata and verify that route, authenticated producer, `messageType`, and selected local schema agree. Remote `$ref` fetching or producer-selected schemas are forbidden.

## Reconciliation

The reconciler scans authoritative deadlines and leases rather than broker queues. It can republish unsent outbox records, fence expired epochs, terminalize timed-out phases, and flag attempts whose result/manifest disposition is incomplete. Reconciliation uses compare-and-set and is itself idempotent. It never assumes absence from a queue proves absence of work.
