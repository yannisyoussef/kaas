# Implementation Status

Status date: 2026-08-28

This document describes repository reality after the outbox relay and RabbitMQ publication vertical slice. Product vision and execution architecture do not imply runtime capability.

## Implemented and validated

- Java 25 multi-module build using the pinned Gradle 9.7.1 wrapper and distribution checksum.
- Spring Boot 4.1.1 API bootstrap.
- Real Actuator health, liveness, and readiness endpoints, verified over HTTP in an application test.
- Non-executing runner bootstrap with a behavioral test of its disabled-execution message.
- Next.js 16.3.3 / React 19.2.8 scaffold with deterministic ESLint, TypeScript checking, a server-rendered page test, production build, lockfile, and production audit.
- Strict Draft 2020-12 compilation for execution command/result, artifact manifest, and live-event schemas, with canonical/minimal/negative fixtures and named semantic contract checks.
- Zero-warning linting of the mixed implemented/proposed OpenAPI contract using pinned Redocly tooling.
- CI jobs for JVM, web, contracts, and Compose validation with explicit read-only permissions and timeouts.
- Spring Security OAuth2 resource-server authentication with RS256, issuer, audience, time, `sub`, and UUID `org_id` validation.
- Trusted-claim tenant context, implicit member authorization, tenant-scoped repository predicates, and concealed cross-tenant 404 behavior.
- Project create/get/list with exact per-organization name uniqueness, audit fields, JPA version, and transactional idempotency.
- Atomic Feature plus revision 1 creation, Feature get/list, immutable revision append/get/history, and concurrency-safe contiguous numbering.
- Project-scoped SecretReference metadata create/get/list with no secret/provider/capability fields.
- Atomic Environment plus revision 1 creation, immutable revision append/get/history, strict typed scalar variables, safe secret bindings, and deterministic content digests.
- Atomic RunProfile plus revision 1 creation, immutable revision append/get/history, exact EnvironmentRevision binding, bounded tags/retry/timeout/artifact settings, same-type plain overrides, and deterministic transitive digests.
- Atomic TestRun intent creation in exact version-1 CREATED state, authenticated get/list, and immutable snapshot reads with strong ETags.
- Exact FeatureRevision selection (1–1000), RunProfileRevision/EnvironmentRevision provenance, materialized effective typed configuration, metadata-only secret bindings, server-owned Karate engine metadata, and a versioned canonical digest.
- PostgreSQL V3 normalized snapshot sealing, composite ownership FKs, initial TestRun invariant trigger, deferred one-to-one snapshot requirement, stable project history index, and no-delete evidence retention.
- Internal transactional scheduling of the single CREATED to QUEUED transition: database-level compare-and-set on organization/run/state/expected runVersion, server-owned queue start and deadline, and semantic runVersion increment to 2.
- Atomic scheduling bundle in one transaction: ExecutionAttempt #1 awaiting claim with no assignment, an immutable queue-time DispatchIntent bound to the sealed snapshot, a run lifecycle event, and exactly one unpublished outbox message.
- Versioned canonical `kaas.execution-dispatch.v1` message digest over length-prefixed fields, independent of JSON serialization order, with a strict machine-validated `execution-dispatch` contract and forbidden-field fixtures.
- PostgreSQL V4 narrowing of the V3 fail-closed TestRun guard to permit only the exact CREATED to QUEUED shape, insert-only attempt/dispatch/event/outbox tables, payload-to-column equality enforcement, and deferred bundle-completeness constraint triggers.
- Semantic transactional run idempotency, including reordered feature-set replay and concurrent first use.
- PostgreSQL-sealed normalized configuration aggregates that reject late child inserts and every post-seal parent/child update or delete.
- Exact UTF-8 source preservation and SHA-256, 512 KiB source validation, 1 MiB streaming request limit, NUL/control/malformed-Unicode rejection, and no Karate parsing.
- Flyway-managed PostgreSQL schema, JPA `ddl-auto=validate`, composite tenant/parent FKs, indexes, uniqueness/check constraints, Feature revision triggers, and deferred configuration aggregate sealing.
- RFC 9457 Problem Details across MVC and security filters, request/trace correlation context, safe structured mutation logs, and Actuator HTTP metrics.
- Signed-JWT HTTP, tenant-IDOR, concurrent idempotency, PostgreSQL Testcontainers, database immutability, transport/content boundaries, two ten-writer configuration revision races, canonical digest vectors, and ArchUnit tests.
- Scheduling idempotency by state and invariants: ten concurrent schedulers yield exactly one semantic winner, one attempt, one dispatch, and one outbox message; repeat scheduling makes no new durable work.
- Generalized, explicitly typed PostgreSQL outbox owning its own immutable payload, with a controlled message-type enum, an optional dispatch reference, and a V5 delivery-scheduling model the database rather than the relay process owns.
- Outbox relay publishing to RabbitMQ with correlated publisher confirms, persistent messages, mandatory routing, bounded deterministic backoff, terminal dispositions retained as evidence, and fail-closed digest verification before publication.
- Relay claim protocol using FOR UPDATE SKIP LOCKED with lease expiry, so multiple relays take disjoint work, a crashed relay strands nothing, and a revived relay cannot overwrite a newer disposition. No database transaction is held across broker I/O.
- At-least-once publication with an explicit, tested crash-after-confirm duplicate window; stable message identity and semantic digest make duplicates safe for a future consumer.
- Production run scheduler moving CREATED to QUEUED in bounded, deterministically ordered batches through the established use case, safe across replicas.
- Idempotent run-create replay now returns the run's current canonical representation and ETag rather than a reconstructed CREATED view, so one resource never advertises two strong validators.
- Final Java 25/Gradle 9.7.1 clean verification: 82 API tests plus 1 runner test passed with zero failures/skips using PostgreSQL and RabbitMQ Testcontainers; contract, web, audit, Compose, and whitespace gates also passed.

## Scaffolded, not product functionality

- The runner prints a status message and cannot execute Karate, shell commands, or external processes.
- The web application contains a landing page and placeholder dashboard with no API client or product data.
- Run create/get/list/snapshot OpenAPI paths are implemented. Cancellation/events/results/artifacts and execution JSON Schemas remain proposed contracts.
- Docker Compose PostgreSQL is configured for the API. RabbitMQ and MinIO are not connected to feature lifecycle.
- Docker Compose binds PostgreSQL, RabbitMQ, and MinIO to loopback and defines development health checks. Configuration validation passes; container startup was not verified because the local Docker daemon was unavailable.

## Designed or proposed

- Future control-plane capabilities beyond versioned execution configuration and a separately deployable execution plane.
- PostgreSQL schemas for orchestration and result metadata beyond the implemented scheduling bundle and outbox delivery state.
- Per-run container isolation as a candidate only; it is not approved as a sufficient hostile-code boundary.
- Product concepts beyond implemented QUEUED scheduling and dispatch publication, including execution, results, artifact storage, and quality gates.
- Orthogonal run lifecycle, cancellation, test outcome, infrastructure outcome, and quality-gate semantics.
- Assignment fencing, leases, phase deadlines beyond the queue deadline, consumer inbox semantics, consumer-side DLQ classification, structured results/artifacts, and bounded SSE replay.

## Planned and intentionally absent

- RabbitMQ consumers, consumer inbox deduplication, or consumer dead-letter handling. Publication is implemented; the queue may hold published dispatch intents that no production code consumes.
- Redis.
- SSE implementation.
- Object-storage integration.
- Secret values, provider mapping, storage, resolution, redemption, capability minting, or injection.
- Karate dependencies, runner images, container launchers, or arbitrary test execution.
- OpenTelemetry instrumentation.
- Lifecycle mutation handlers beyond CREATED to QUEUED, consumer inbox, lease reconciliation, quality evaluation, outbox retention policy, and durable run event storage beyond the scheduling transition record.

## Toolchain decisions

- Java 25 is the project compilation, test, and runtime target.
- Gradle 9.7.1 is the current stable wrapper version selected on 2026-08-27. Gradle 9.8 is a milestone and is not used.
- Spring Boot 4.1.1 explicitly supports Java 25 and Gradle 9.x.
- Node.js 24 LTS is the frontend and contract-tooling baseline. Node.js 20 is end-of-life and is not supported by this repository.
- Next.js 16.3.3 is the patched Active LTS foundation selected after the August 2026 security release.

See `docs/adr/003-java-spring-boot.md` for tradeoffs and revisit conditions.

## Verification commands

```text
./gradlew clean check
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web audit --omit=dev
npm --prefix packages/api-contracts ci
npm --prefix packages/api-contracts run validate:schemas
npm --prefix packages/api-contracts run lint:openapi
docker compose -f infrastructure/local/docker-compose.yml config
git diff --check
```

Exact results for this repair are recorded in `FOUNDATION_REPAIR_REPORT.md`.

## Contract/lifecycle architecture completed as design

This iteration resolves KAA-003, KAA-007, KAA-013, and KAA-015 at the **proposed contract/design level only**:

- Four strict contracts plus adversarial fixtures are machine-validated.
- The transition/race/timeout/lease model and transport-neutral at-least-once protocol are documented.
- Run creation/read/cancellation/events/results/artifacts and RFC 9457-style errors are specified in OpenAPI.
- Result, artifact, quality-gate, SSE replay, observability propagation, compatibility, and contract threat mitigations are documented.

No item above is runtime behavior. KAA-004 remains open: Docker/host/daemon/network/secret/artifact isolation still needs a dedicated hostile-execution security architecture and executable release gate.

## Current vertical slice

Authenticated, organization-scoped Project/FeatureRevision and Environment/RunProfile lifecycles, TestRun/RunSnapshot persistence, the single CREATED to QUEUED scheduling transition, and at-least-once publication of the resulting dispatch intent to RabbitMQ are implemented. A background scheduler triggers the transition and a separate relay loop publishes the outbox; PostgreSQL remains authoritative for run state, attempt identity, dispatch intent, and delivery state. Queue-time dispatch intent is deliberately not a claim-time execution command: no assignment epoch, lease, or runtime capability exists yet. Worker claim, consumption, and execution remain disabled until their separate protocol and hostile-execution release gates are implemented and validated.

## Security gate

Arbitrary execution remains disabled. Do not add Karate or a container launcher until the proposed execution security ADR has enforceable controls and adversarial tests for host isolation, egress, secrets, resources, timeouts, logs, artifacts, and cleanup.

See `CONTRACT_LIFECYCLE_ARCHITECTURE_REPORT.md` for the decisions, adversarial review, changed files, verification evidence, and deferred implementation work.

See `PROJECT_FEATURE_SLICE_REPORT.md` for the implemented API/schema decisions, verification evidence, residual risks, and independent specialist reviews.

See `ENVIRONMENT_RUN_PROFILE_SLICE_REPORT.md` for the versioned-configuration model, canonicalization, database sealing, concurrency/idempotency evidence, and independent specialist reviews.

See `TEST_RUN_INTENT_SLICE_REPORT.md` for the CREATED-only lifecycle boundary, snapshot canonicalization/persistence, API contract, security evidence, runner-command mapping, and independent reviews.

See `SCHEDULING_OUTBOX_SLICE_REPORT.md` for the recovered scheduling slice: the queue-time/claim-time protocol correction, transactional bundle, compare-and-set concurrency evidence, persistence guards, contract changes, security review, and independent reviews.

See `RABBITMQ_OUTBOX_RELAY_SLICE_REPORT.md` for the relay slice: the generalized outbox decision, delivery-scheduling model, relay claim protocol, publisher confirms, retry and terminal policy, at-least-once crash-window analysis, scheduler trigger, health and observability, security review, and independent reviews.
