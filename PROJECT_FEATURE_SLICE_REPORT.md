# Project and FeatureRevision Slice Report

Status date: 2026-08-27

## Outcome

The first KaaS control-plane vertical slice is implemented in `apps/api`: external bearer-JWT authentication, trusted-claim tenant context, organization-scoped Project APIs, logical Features, immutable FeatureRevisions, PostgreSQL/Flyway/JPA persistence, transactional idempotency, RFC 9457 errors, correlation, OpenAPI, and automated tests.

Arbitrary execution remains impossible in this slice. The API runtime graph contains no Karate, RabbitMQ, MinIO, shell-launch, or runner dependency. Feature source is opaque stored content and is never parsed or executed.

## API surface

Implemented endpoints:

- `POST /api/v1/projects`
- `GET /api/v1/projects`
- `GET /api/v1/projects/{projectId}`
- `POST /api/v1/projects/{projectId}/features`
- `GET /api/v1/projects/{projectId}/features`
- `GET /api/v1/projects/{projectId}/features/{featureId}`
- `POST /api/v1/projects/{projectId}/features/{featureId}/revisions`
- `GET /api/v1/projects/{projectId}/features/{featureId}/revisions`
- `GET /api/v1/projects/{projectId}/features/{featureId}/revisions/{revisionId}`

Feature creation atomically creates revision 1. Feature representations contain only stable identity/audit fields, so a later idempotency replay remains byte-semantically equivalent even after more revisions exist. Revision collections return metadata without source; the item endpoint returns the exact source. There are no update or delete routes for revisions. All POSTs require `Idempotency-Key`; committed replays return the original resource and Location with `Idempotency-Replayed: true`.

The OpenAPI document identifies Project/Feature operations as implemented and preserves every prior Run path as proposed only.

## Authentication, tenancy, and authorization

Spring Security runs as a stateless OAuth 2.0 resource server. Deployment properties specify the trusted issuer, JWK set, audience, and RS256 allowlist. Non-routable checked-in OIDC defaults fail closed; there is no development authentication bypass.

A token is accepted only after signature, issuer, audience, expiry/not-before, nonblank control-free `sub`, and one canonical UUID `org_id` are validated. A valid trusted organization claim grants simple member access in this slice. Roles beyond membership are deferred.

Controllers accept no organization field. The application passes a typed `TenantPrincipal` inward. Every lookup includes trusted organization plus full project/feature parent scope; no API path uses unscoped `findById` followed by an ownership check. Cross-tenant and absent resources use the same `404 NOT_FOUND` problem shape. Collection predicates are organization-scoped.

The `organizations` table is a minimal internal ownership anchor materialized on the first authorized mutation. The external identity system owns organization lifecycle, and the slice exposes no organization management API.

## Persistence and migration design

Flyway migration `V1__project_feature_slice.sql` defines:

- `organizations`
- `projects`
- `features`
- `feature_revisions`
- `api_idempotency_keys`

The migration refuses a non-UTF8 database. PostgreSQL constraints enforce non-null fields, exact per-organization project-name uniqueness, per-project logical-path uniqueness, safe path structure, UTF-8 source byte bounds, digest format, positive revision numbers, idempotency-key shape, and composite organization/project/feature ownership. Query indexes match tenant lists and revision history access. Foreign keys have no delete cascade.

Hibernate is configured with `ddl-auto=validate` and Open Session in View is disabled. JPA entities remain package-private infrastructure types; explicit domain records are returned to API adapters.

Project carries server-controlled create/update audit fields and a JPA optimistic version. Feature identity is stable in this no-update slice. Revisions carry creation audit only.

## Source, digest, immutability, and concurrency

The accepted source range is 1–524288 bytes after strict UTF-8 encoding of the decoded JSON string. The API also imposes a 1048576-byte streaming request-body ceiling for requests with or without `Content-Length`. Empty source, NUL, forbidden C0 controls, and malformed/unpaired Unicode are rejected. TAB, LF, and CR remain valid.

The server hashes the exact stored source UTF-8 bytes with SHA-256 and renders `sha256:` plus lowercase hex. It does not trim, normalize Unicode, convert CRLF/LF, parse Karate, interpolate configuration, or inspect executable semantics.

FeatureRevision has no mutation persistence port. PostgreSQL additionally rejects direct UPDATE or DELETE through a trigger. Revision creation validates and hashes before acquiring a tenant-scoped pessimistic lock on the Feature row, consumes and increments its persisted `next_revision_number`, and inserts in one transaction. Rollback restores the counter; a composite unique constraint is defense in depth.

## Idempotency

Idempotency scope is `(organization, principal, operation, parent path, key)`. Keys are 8–128 URL-safe ASCII characters and are never logged. Fingerprints are versioned length-prefixed SHA-256 values over fixed-order typed fields. Source contributes its exact UTF-8 representation; JSON property ordering and whitespace do not change the fingerprint.

A PostgreSQL transaction advisory lock serializes concurrent first use of a scope/key. The idempotency row and resource commit in one transaction. Same scope/key/fingerprint replays the resource; another fingerprint returns `409 IDEMPOTENCY_CONFLICT`. Tenant and principal scope prevents replay leakage.

## Errors and observability

MVC exceptions and Spring Security filter failures both return `application/problem+json`. Stable extensions include `code` and server-generated `requestId`; validation can include sorted safe JSON pointers. Error bodies never copy submitted values, source, tokens, SQL, table/constraint names, stack traces, or concealed tenant identity. Authentication failures include `WWW-Authenticate: Bearer`.

Every response receives `X-Request-ID`. Logstash-format logs carry request ID and a valid incoming W3C trace ID when available. Mutation logs contain operation and trusted resource/tenant IDs, but never source, JWTs, request bodies, digests, or idempotency keys. Actuator/Micrometer provides aggregate HTTP count, error, and latency meters without resource IDs as labels.

## Test coverage added

- Real random-port HTTP tests with RSA-signed JWTs: missing, malformed, wrong-signature, wrong-audience, expired, missing-subject, missing/malformed-organization, and valid-token behavior.
- Project create/get/list, audit/version, Location, unknown-field rejection, exact database uniqueness, same-key replay, changed-body conflict, cross-tenant concealment/list isolation, and cross-tenant key independence.
- Atomic Feature/revision 1 creation, append/get/history, source omission from lists, old-revision preservation, digest/CRLF fidelity, cross-tenant nested access, and no revision update/delete API.
- Ten concurrent HTTP revision writers with unique content; assertions require all successes and contiguous unique numbers without assuming completion order.
- Exact 512 KiB acceptance, one-byte oversize rejection, NUL/control/malformed-Unicode unit cases, and exact UTF-8 hashing.
- Direct PostgreSQL checks for successful Flyway history, composite tenant ownership, duplicate revision numbers, source-size constraints, and UPDATE/DELETE immutability trigger.
- ArchUnit checks for framework-free domain, API-to-infrastructure separation, and no runner/Karate dependency.

The PostgreSQL suite uses Testcontainers 2.0.5 and `postgres:16.10-alpine`, has no H2 dependency, no `disabledWithoutDocker`, no container reuse, and one Gradle test fork.

## Verification evidence

Passed in this environment:

- `GRADLE_USER_HOME=/private/tmp/kaas-review-gradle ./gradlew clean check --warning-mode all --no-daemon` on Java 25.0.3 and Gradle 9.7.1, including the mandatory PostgreSQL Testcontainers suite and the control-plane execution-dependency guard.
- Java 25 API production and test compilation.
- API `bootJar` creation.
- SourcePolicy, ArchUnit, signed-JWT HTTP, PostgreSQL migration/constraint, idempotency-race, tenant-isolation, request-boundary, and concurrency tests (13/13).
- Runner behavioral test confirming execution remains disabled.
- OpenAPI recommended-rule lint with zero findings.
- All execution JSON Schema fixtures and semantic invariants.
- Node 24 web install, lint, typecheck, render test, production build, and production dependency audit (`0 vulnerabilities`).
- Compose configuration validation.
- Runtime dependency graph inspection: no Karate, RabbitMQ, or MinIO dependency in `apps/api`.
- `git diff --check`.

Docker evidence:

- An earlier primary-agent `docker info` attempt could not reach the daemon. The final escalated root `clean check` did reach Docker and passed the mandatory Testcontainers suite, so the current evidence supersedes that earlier probe.
- The independent backend/database reviewer ran `GRADLE_USER_HOME=/private/tmp/kaas-review-gradle ./gradlew :apps:api:test --rerun-tasks --no-daemon` against Testcontainers 2.0.5 and `postgres:16.10-alpine` on Java 25.0.3. The result was `BUILD SUCCESSFUL`; all 13 tests passed.
- The suite remains mandatory under `./gradlew clean check`, including the GitHub-hosted backend job. There is no silent no-Docker skip.

## Independent specialist reviews

Three independent read-only reviews were completed after implementation:

- **Backend/database:** found PostgreSQL NUL advisory-lock input, missing Boot 4 Flyway starter, CHAR/VARCHAR validation, JDBC Instant binding, assigned-ID persistence, readiness, and Gradle/Testcontainers obsolescence issues. All correctness blockers were remediated; the reviewer reran 13/13 Docker-backed tests green and reported no remaining slice blocker.
- **API/security:** found non-expiring JWT acceptance, mutable Feature replay metadata, retention wording, missing safe pointers/diagnostics, Unicode edge handling, OpenAPI gaps, and adversarial test gaps. Required `exp`, stable Feature representations, safe pointer/diagnostic behavior, strict Unicode, contract corrections, and focused tests were added. No tenant-isolation bypass or source/token leak was found.
- **QE/test:** requested concurrent idempotency proof, lossless concurrency reads, mixed-parent IDOR cases, transport/chunked/malformed UTF-8 tests, bounded futures, feature/list/method negatives, and dependency-level execution guards. Those cases and guards were added. Log secrecy/correlation remain primarily statically protected rather than capture-tested.

## Residual risks and deferred work

- Organization lifecycle/provisioning and role-based authorization are intentionally minimal; only trusted-issuer membership is implemented.
- Idempotency retention cleanup is not implemented. Rows are bounded per mutation but currently persist indefinitely.
- Source is stored in PostgreSQL for this bounded slice; storage growth and retention need measurement before large-scale use.
- Rate limiting is not implemented. Request/source bounds and authentication reduce exposure but do not replace edge quotas.
- Full distributed tracing/export infrastructure is deferred; only correlation and built-in metrics are implemented.
- TestRun, execution outbox/inbox, RabbitMQ, SSE, environments/profiles, secrets, MinIO, Karate, runner execution, container launching, quality-gate execution, and hostile-code sandboxing remain absent.

## Files changed for this slice

- `apps/api/build.gradle.kts` and root Gradle configuration: Spring Security, JPA, Flyway, PostgreSQL, Testcontainers, ArchUnit, Java 25 test-agent support, and the execution-dependency guard.
- `apps/api/src/main/java/com/kaas/api/controlplane/**`: explicit Project, Feature, and FeatureRevision API, application, domain, and persistence components.
- `apps/api/src/main/java/com/kaas/api/security/**`: external-OIDC-shaped bearer authentication and trusted tenant-principal mapping.
- `apps/api/src/main/java/com/kaas/api/shared/**`: request limits, correlation, and centralized RFC 9457 error translation.
- `apps/api/src/main/resources/db/migration/V1__project_feature_slice.sql`: PostgreSQL schema, relational constraints, indexes, and revision immutability trigger.
- `apps/api/src/test/**`: signed-JWT HTTP integration, PostgreSQL/Testcontainers, concurrency, content-boundary, and architecture tests.
- `docs/api/openapi-v1.yaml`, `docs/adr/014-project-feature-revision-slice.md`, `docs/architecture/project-feature-slice.md`, security/architecture documentation, `README.md`, and `IMPLEMENTATION_STATUS.md`: contract and operational documentation.
- `.env.example`, `infrastructure/local/docker-compose.yml`, and `.github/workflows/ci.yml`: local PostgreSQL/OIDC configuration and mandatory CI verification.

## Recommended next slice

Build environment and run-profile configuration as a separate tenant-scoped control-plane slice, including encrypted secret references and authorization boundaries, but still without starting Karate or arbitrary containers. That establishes explicit, reviewable execution inputs before introducing TestRun orchestration, messaging, artifact storage, or runner activation.
