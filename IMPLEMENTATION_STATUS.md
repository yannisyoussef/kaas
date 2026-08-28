# Implementation Status

Status date: 2026-08-28

This document describes repository reality after the transactional run scheduling, execution attempt, and outbox vertical slice. Product vision and execution architecture do not imply runtime capability.

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
- Final Java 25/Gradle 9.7.1 clean verification: 51 API tests plus 1 runner test passed with zero failures/skips; contract, web, audit, Compose, and whitespace gates also passed.

## Scaffolded, not product functionality

- The runner prints a status message and cannot execute Karate, shell commands, or external processes.
- The web application contains a landing page and placeholder dashboard with no API client or product data.
- Run create/get/list/snapshot OpenAPI paths are implemented. Cancellation/events/results/artifacts and execution JSON Schemas remain proposed contracts.
- Docker Compose PostgreSQL is configured for the API. RabbitMQ and MinIO are not connected to feature lifecycle.
- Docker Compose binds PostgreSQL, RabbitMQ, and MinIO to loopback and defines development health checks. Configuration validation passes; container startup was not verified because the local Docker daemon was unavailable.

## Designed or proposed

- Future control-plane capabilities beyond versioned execution configuration and a separately deployable execution plane.
- PostgreSQL schemas for orchestration and result metadata beyond the implemented CREATED/QUEUED scheduling bundle.
- Per-run container isolation as a candidate only; it is not approved as a sufficient hostile-code boundary.
- Product concepts beyond implemented QUEUED scheduling, including execution, results, artifact storage, and quality gates.
- Orthogonal run lifecycle, cancellation, test outcome, infrastructure outcome, and quality-gate semantics.
- Assignment fencing, leases, phase deadlines beyond the queue deadline, outbox publication and consumer inbox semantics, retries/DLQ classification, structured results/artifacts, and bounded SSE replay.

## Planned and intentionally absent

- RabbitMQ publishers/consumers, topology, retries, DLQ, or broker idempotency behavior. The durable outbox exists but nothing reads or publishes it, and the API runtime dependency graph contains no AMQP client.
- Redis.
- SSE implementation.
- Object-storage integration.
- Secret values, provider mapping, storage, resolution, redemption, capability minting, or injection.
- Karate dependencies, runner images, container launchers, or arbitrary test execution.
- OpenTelemetry instrumentation.
- Lifecycle mutation handlers beyond CREATED to QUEUED, outbox publication relay, consumer inbox, lease reconciliation, quality evaluation, and durable run event storage beyond the scheduling transition record.

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

Authenticated, organization-scoped Project/FeatureRevision and Environment/RunProfile lifecycles, TestRun/RunSnapshot persistence, and the single CREATED to QUEUED scheduling transition are implemented. Scheduling is an internal application use case with no public endpoint; it durably records an attempt, an immutable queue-time dispatch intent, and an unpublished outbox message. Queue-time dispatch intent is deliberately not a claim-time execution command: no assignment epoch, lease, or runtime capability exists yet. Broker publication, worker claim, and execution remain disabled until their separate protocol and hostile-execution release gates are implemented and validated.

## Security gate

Arbitrary execution remains disabled. Do not add Karate or a container launcher until the proposed execution security ADR has enforceable controls and adversarial tests for host isolation, egress, secrets, resources, timeouts, logs, artifacts, and cleanup.

See `CONTRACT_LIFECYCLE_ARCHITECTURE_REPORT.md` for the decisions, adversarial review, changed files, verification evidence, and deferred implementation work.

See `PROJECT_FEATURE_SLICE_REPORT.md` for the implemented API/schema decisions, verification evidence, residual risks, and independent specialist reviews.

See `ENVIRONMENT_RUN_PROFILE_SLICE_REPORT.md` for the versioned-configuration model, canonicalization, database sealing, concurrency/idempotency evidence, and independent specialist reviews.

See `TEST_RUN_INTENT_SLICE_REPORT.md` for the CREATED-only lifecycle boundary, snapshot canonicalization/persistence, API contract, security evidence, runner-command mapping, and independent reviews.

See `SCHEDULING_OUTBOX_SLICE_REPORT.md` for the recovered scheduling slice: the queue-time/claim-time protocol correction, transactional bundle, compare-and-set concurrency evidence, persistence guards, contract changes, security review, and independent reviews.
