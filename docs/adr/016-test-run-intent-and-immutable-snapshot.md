# ADR-016: Persist TestRun intent with an immutable execution snapshot

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** TestRun creation/read/list and immutable RunSnapshot persistence only

## Context

KaaS needs a durable run identity before any scheduling or execution capability is introduced. A request names exact FeatureRevisions and one exact RunProfileRevision. The profile already pins an exact EnvironmentRevision. Later changes to feature, environment, or profile histories must not change what an accepted run means.

The runner command contract contains attempt-owned and security-sensitive material—command/message identities, assignment epochs, deadlines, source-bundle capabilities, runtime secret capabilities, and network policy. Those values cannot be truthfully derived during this slice and must not be fabricated.

## Decision

Persist `TestRun` and a separate one-to-one `RunSnapshot` atomically. A new run is exactly version 1, `CREATED`, cancellation `NOT_REQUESTED`, outcomes unset, and quality gate `NOT_EVALUATED`. Creation returns `202 Accepted` because the intent is durable but no scheduling or publication occurs.

The request contains only `featureRevisionIds` (1–1000) and `runProfileRevisionId`. Feature order is non-semantic. Duplicate revision IDs, multiple revisions of one Feature, and logical-path collisions are rejected. Every input is resolved with trusted organization/project predicates; missing and foreign inputs are concealed as the same 404.

The snapshot stores:

- canonical FeatureRevision identity, revision number, copied logical path, and source digest;
- exact RunProfileRevision and pinned EnvironmentRevision identity, revision number, and digest;
- Environment plain configuration overwritten by same-type profile plain overrides;
- secret bindings as key plus SecretReference UUID metadata only;
- canonical tags, retry, timeout, parallelism, artifact settings; and
- server-owned `{engine: KARATE, version}` metadata.

The snapshot does not store feature source bodies, secret values, provider locations, credentials, capabilities, source-bundle references, network-policy placeholders, attempts, commands, deadlines, or broker data.

Snapshot content uses `kaas.run-snapshot-content.v1` with explicit collection cardinalities, four-byte big-endian length-prefixed UTF-8 fields, and a stable total order. The digest covers every execution-semantic field and excludes run ID, request ordering, timestamps, actor, lifecycle, and JSON formatting. The request fingerprint remains distinct and covers only normalized client intent, so an engine configuration change cannot invalidate a legitimate replay.

PostgreSQL stores the snapshot as a normalized sealed aggregate. Composite foreign keys bind the full organization/project/parent identity. A false-to-true seal is the only snapshot header update; late child insert, every subsequent update, and deletion are rejected by triggers. A deferred trigger requires every inserted TestRun to have exactly one matching sealed snapshot at commit.

`queueDeadlineAt` is absent at `CREATED`. A future scheduler starts queue timing atomically with `CREATED → QUEUED`. It must also create the execution attempt/plan and outbox; none exist here.

## Alternatives considered

### Store only revision references

Rejected. It would force later reads to re-resolve configuration/defaults and would not prove the exact server engine or effective configuration accepted at creation.

### Store one JSONB snapshot

Rejected for this slice. Normalized rows provide stronger composite ownership, typed sum-shape, uniqueness, secret-reference, and post-seal invariants in PostgreSQL.

### Reuse the runner command schema

Rejected. It would either invent scheduling-owned values or prematurely add secret, bundle, network, broker, and execution capabilities.

### Publish immediately on create

Rejected. Scheduling, attempts, outbox/inbox, RabbitMQ, and execution security are deliberately outside scope.

## Consequences

- A run has a stable reproducibility record before execution is enabled.
- Reads never merge current configuration or dereference secrets.
- Storage grows with selected feature metadata and materialized configuration, but feature source is not duplicated.
- Future scheduling needs a distinct immutable execution-attempt plan containing bundle, network-policy, capability, identity, fencing, and deadline data.
- Future lifecycle updates must increment `runVersion`; fields represented by the strong run ETag must change only with that version.

## Verification

Java 25/Gradle 9.7.1 tests cover canonical ordering/digest, lifecycle oracle, PostgreSQL V3 migration, API creation/read/list/snapshot, tenant concealment, mass-assignment rejection, semantic idempotency and concurrency, 1/1000/1001 boundaries, reproducibility, and database sealing. Contract lint, architecture guards, dependency checks, and the full repository test suite remain release gates.
