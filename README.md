# KaaS — Karate as a Service

> **Execute. Automate. Assure.**

KaaS is intended to become a self-service quality engineering platform for isolated, asynchronous Karate execution and structured results. The repository implements authenticated, organization-scoped Projects, immutable FeatureRevisions, versioned execution configuration, and CREATED TestRun intent with a sealed immutable execution snapshot backed by PostgreSQL.

Arbitrary test execution is disabled. No user-supplied feature runs in the API or runner scaffold.

## Capability status

| Capability | Status | Evidence / boundary |
|---|---|---|
| Java multi-module build | IMPLEMENTED + VALIDATED | Java 25, Gradle 9.7.1 wrapper, `./gradlew clean check` |
| Spring Boot control plane | IMPLEMENTED | JWT resource server, RFC 9457 errors, Project/FeatureRevision and versioned-configuration APIs, Flyway/JPA/JDBC/PostgreSQL |
| Project and immutable feature revisions | IMPLEMENTED + VALIDATED | Signed-JWT HTTP and PostgreSQL Testcontainers suite passed 13/13 in independent backend review; primary local daemon was unavailable |
| Environment and immutable revisions | IMPLEMENTED + VALIDATED | Typed scalar variables, metadata-only secret bindings, canonical digests, sealed relational aggregates, and 10-writer concurrency coverage |
| RunProfile and immutable revisions | IMPLEMENTED + VALIDATED | Exact EnvironmentRevision pinning, bounded execution intent, same-type plain overrides, canonical digests, and 10-writer concurrency coverage |
| SecretReference metadata | IMPLEMENTED + VALIDATED | Project-scoped identity/name/audit only; no value, provider/path, credential, resolve, reveal, or redemption capability |
| TestRun intent and immutable RunSnapshot | IMPLEMENTED + VALIDATED | 202 create, get/list/snapshot, semantic idempotency, exact initial dimensions, canonical digest, sealed V3 aggregate, no scheduling |
| Runner bootstrap | SCAFFOLDED + VALIDATED | Reports that execution is disabled; launches no external process |
| Next.js web scaffold | IMPLEMENTED + VALIDATED | Next.js 16.3.3; lint, typecheck, render test, build, production audit |
| Contract tooling | IMPLEMENTED + VALIDATED | Strict AJV schemas/fixtures plus semantic checks; proposed contracts only |
| OpenAPI contract | IMPLEMENTED + PROPOSED | Run create/get/list/snapshot and configuration APIs are implemented; cancellation/events/results/artifacts remain proposed |
| Local PostgreSQL/RabbitMQ/MinIO definitions | SCAFFOLDED | Loopback-only Compose config with development health checks |
| Modular control-plane boundaries | IMPLEMENTED | Capability packages with inward ports and ArchUnit enforcement |
| PostgreSQL persistence | IMPLEMENTED | Flyway schema, JPA validation, tenant composite FKs, immutable-revision trigger, query indexes |
| RabbitMQ, SSE, object storage integration | PLANNED | No publisher, consumer, stream, or storage adapter exists |
| Docker execution sandbox | DESIGNED, NOT APPROVED | ADR-006 is proposed; no launcher or runner image exists |
| Authentication and product APIs | IMPLEMENTED FOR CURRENT SLICES | External OIDC-compatible bearer JWT; trusted tenant claims only; full-parent scoping and cross-tenant concealment |
| Run lifecycle/results/messaging/SSE semantics | DESIGNED + VALIDATED | Proposed ADRs, canonical docs, strict schemas, fixtures, and OpenAPI; no runtime adapters |
| Karate execution | PLANNED + DISABLED | No dependency, launcher, consumer, or arbitrary execution path |

Status meanings: **IMPLEMENTED** is present in code/tooling; **VALIDATED** has an automated passing check; **SCAFFOLDED** has only a minimal executable or contract shell; **DESIGNED** is documented intent; **PLANNED** has no active implementation.

## Proposed product architecture

```mermaid
flowchart LR
  User["User / CI client"] --> API["Spring Boot control plane"]
  Web["Next.js web"] -. no product integration .-> API
  API --> DB[(PostgreSQL)]
  API -. proposed .-> Queue[(RabbitMQ)]
  Worker["Runner worker"] -. proposed .-> Queue
  Worker -. proposed .-> Sandbox["Ephemeral isolated runtime"]
  Sandbox -. proposed .-> Objects[(Object storage)]
```

The diagram is a target, not a deployment claim. The control plane must never execute user-controlled test content. Execution remains disabled until a separate security architecture review approves and verifies the launcher, runtime, network, secret, resource, and artifact controls.

## Repository layout

- `apps/api` — JWT-secured Project/FeatureRevision, versioned-configuration, and CREATED TestRun/snapshot control plane
- `apps/web` — Next.js frontend scaffold and render test
- `services/runner` — non-executing runner bootstrap
- `packages/api-contracts` — JSON Schemas, fixtures, and OpenAPI validation tooling
- `infrastructure/local` — loopback-only local dependency definitions
- `docs` — product intent, proposed architecture, security requirements, and ADRs
- `.github/workflows` — foundation verification

## Toolchain

- Java 25
- Gradle 9.7.1 through the committed wrapper; no global Gradle installation
- Spring Boot 4.1.1
- Node.js 24 LTS
- Next.js 16.3.3 / React 19.2.8
- Docker Compose

Stable supported releases are preferred. Preview, milestone, release-candidate, and nightly versions are not selected merely because their version number is higher.

## Verify the repository

```text
./gradlew clean check
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web audit --omit=dev
npm --prefix packages/api-contracts ci
npm --prefix packages/api-contracts test
docker compose -f infrastructure/local/docker-compose.yml config
git diff --check
```

## Local API and infrastructure

The API connects to the Compose PostgreSQL defaults through `KAAS_DATABASE_*`. RabbitMQ and MinIO remain present but are intentionally not wired into this slice. Product endpoints also need a trusted issuer, JWK set, and audience; the checked-in non-routable OIDC defaults fail closed. Defaults bind infrastructure ports to `127.0.0.1`.

```text
cp .env.example .env
docker compose -f infrastructure/local/docker-compose.yml config
docker compose -f infrastructure/local/docker-compose.yml up -d
KAAS_OIDC_ISSUER_URI=https://issuer.example \
KAAS_OIDC_JWK_SET_URI=https://issuer.example/.well-known/jwks.json \
KAAS_OIDC_AUDIENCE=kaas-api \
./gradlew :apps:api:bootRun
```

The checked-in values are local-development defaults, not production secret management.

## Documentation

- [Implementation status](IMPLEMENTATION_STATUS.md)
- [Independent architecture review](CODEX_ARCHITECTURE_REVIEW.md)
- [Foundation repair report](FOUNDATION_REPAIR_REPORT.md)
- [Contract and lifecycle architecture report](CONTRACT_LIFECYCLE_ARCHITECTURE_REPORT.md)
- [Project/Feature slice report](PROJECT_FEATURE_SLICE_REPORT.md)
- [Environment/RunProfile slice report](ENVIRONMENT_RUN_PROFILE_SLICE_REPORT.md)
- [Implemented Project/Feature architecture](docs/architecture/project-feature-slice.md)
- [Implemented Environment/RunProfile architecture](docs/architecture/environment-run-profile-slice.md)
- [Implemented TestRun intent/snapshot architecture](docs/architecture/test-run-intent-slice.md)
- [Architecture decisions](docs/adr/README.md)
- [Security release requirements](docs/security/threat-model.md)

## Scope discipline

This slice deliberately implements only TestRun intent and immutable snapshot persistence. It does not implement scheduling, lifecycle mutation, execution attempts, RabbitMQ messaging, outbox/inbox, SSE, secret values/storage/redemption, source bundles, object storage, Karate parsing/execution, a container launcher, network enforcement, results/artifacts, quality-gate execution, or full OpenTelemetry infrastructure.
