# Implementation Status

Status date: 2026-08-28

This document describes repository reality after the Environment/RunProfile versioned-configuration vertical slice. Product vision and run architecture do not imply runtime capability.

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
- PostgreSQL-sealed normalized configuration aggregates that reject late child inserts and every post-seal parent/child update or delete.
- Exact UTF-8 source preservation and SHA-256, 512 KiB source validation, 1 MiB streaming request limit, NUL/control/malformed-Unicode rejection, and no Karate parsing.
- Flyway-managed PostgreSQL schema, JPA `ddl-auto=validate`, composite tenant/parent FKs, indexes, uniqueness/check constraints, Feature revision triggers, and deferred configuration aggregate sealing.
- RFC 9457 Problem Details across MVC and security filters, request/trace correlation context, safe structured mutation logs, and Actuator HTTP metrics.
- Signed-JWT HTTP, tenant-IDOR, concurrent idempotency, PostgreSQL Testcontainers, database immutability, transport/content boundaries, two ten-writer configuration revision races, canonical digest vectors, and ArchUnit tests.

## Scaffolded, not product functionality

- The runner prints a status message and cannot execute Karate, shell commands, or external processes.
- The web application contains a landing page and placeholder dashboard with no API client or product data.
- Run OpenAPI paths and execution JSON Schemas remain proposed contracts. Project/Feature/SecretReference/Environment/RunProfile OpenAPI paths describe implemented runtime behavior.
- Docker Compose PostgreSQL is configured for the API. RabbitMQ and MinIO are not connected to feature lifecycle.
- Docker Compose binds PostgreSQL, RabbitMQ, and MinIO to loopback and defines development health checks. Configuration validation passes; container startup was not verified because the local Docker daemon was unavailable.

## Designed or proposed

- Future control-plane capabilities beyond versioned execution configuration and a separately deployable execution plane.
- PostgreSQL schemas for future run orchestration and result metadata.
- Per-run container isolation as a candidate only; it is not approved as a sufficient hostile-code boundary.
- Product concepts beyond implemented projects, immutable feature/configuration revisions, and metadata-only secret references, including asynchronous runs, results, artifact storage, and quality gates.
- Orthogonal run lifecycle, cancellation, test outcome, infrastructure outcome, and quality-gate semantics.
- Optimistic run concurrency, attempt/assignment fencing, phase deadlines, at-least-once outbox/inbox semantics, retries/DLQ classification, structured results/artifacts, and bounded SSE replay.

## Planned and intentionally absent

- RabbitMQ publishers/consumers, topology, retries, DLQ, or idempotency behavior.
- Redis.
- SSE implementation.
- Object-storage integration.
- Secret values, provider mapping, storage, resolution, redemption, capability minting, or injection.
- Karate dependencies, runner images, container launchers, or arbitrary test execution.
- OpenTelemetry instrumentation.
- Run semantic validation, execution outbox/inbox tables, lease reconciliation, quality evaluation, and durable event storage.

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

Authenticated, organization-scoped Project/FeatureRevision and Environment/RunProfile configuration lifecycles are implemented. Logical configuration identities own contiguous immutable revision histories; RunProfileRevision pins an exact EnvironmentRevision. SecretReference is non-authorizing metadata only. The Docker-backed suite is mandatory and not auto-skipped. TestRun persistence/orchestration should follow only as another bounded control-plane slice; execution must remain disabled until the hostile-execution release gate is validated.

## Security gate

Arbitrary execution remains disabled. Do not add Karate or a container launcher until the proposed execution security ADR has enforceable controls and adversarial tests for host isolation, egress, secrets, resources, timeouts, logs, artifacts, and cleanup.

See `CONTRACT_LIFECYCLE_ARCHITECTURE_REPORT.md` for the decisions, adversarial review, changed files, verification evidence, and deferred implementation work.

See `PROJECT_FEATURE_SLICE_REPORT.md` for the implemented API/schema decisions, verification evidence, residual risks, and independent specialist reviews.

See `ENVIRONMENT_RUN_PROFILE_SLICE_REPORT.md` for the versioned-configuration model, canonicalization, database sealing, concurrency/idempotency evidence, and independent specialist reviews.
