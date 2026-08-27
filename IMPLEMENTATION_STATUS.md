# Implementation Status

## Created

- Product vision and constrained MVP scope.
- System context, container architecture, execution lifecycle, domain model, and security boundaries.
- Threat model and ADRs for the major architectural choices.
- Versioned API, runner-command, runner-result, and live-event contracts.
- Monorepo directories for web, API, runner, contracts, infrastructure, tests, and documentation.
- Minimal Next.js web application.
- Minimal Spring Boot API application with health endpoint and Problem Details-style error shape.
- Runner worker bootstrap that consumes only a typed command and performs no user test execution.
- Docker Compose configuration for PostgreSQL, RabbitMQ, Redis, and MinIO.
- GitHub Actions validation workflow and repository hygiene files.

## Decisions made

- A modular monolith is the initial control plane; execution is a separate worker boundary.
- Java 21/Spring Boot is the backend foundation; Next.js/TypeScript is the frontend foundation.
- PostgreSQL is the system of record; object storage holds generated artifacts.
- RabbitMQ is hidden behind an application port so the transport can change later.
- SSE is the initial live-log transport.
- Test outcome and infrastructure outcome are separate dimensions; `TEST_FAILED` is distinct from `EXECUTION_FAILED`.
- Secrets are references to an external provider, never values in API responses or logs.
- No arbitrary feature file is executed by the API or the bootstrap worker.

## Assumptions

- Initial tenancy is organization/project scoped, with authentication integration deferred behind an identity port.
- A project owns immutable feature revisions for reproducibility; the first implementation slice may use a local revision store before full persistence.
- Run creation accepts an idempotency key and returns the existing run for a matching request.
- Environment/profile resolution is deterministic: global, project, environment, profile, then explicit run override; later layers win.
- The first runner implementation uses a safe no-op/bootstrap task until sandbox review is complete.

## Commands

With Java 21 and Docker installed:

```text
./gradlew test
npm --prefix apps/web ci
npm --prefix apps/web run build
docker compose -f infrastructure/local/docker-compose.yml up -d
```

The API runs on `http://localhost:8080`; the web app runs on `http://localhost:3000`.

## Test commands

- API: `./gradlew :apps:api:test`
- Runner: `./gradlew :services:runner:test`
- Web: `npm --prefix apps/web run lint` and `npm --prefix apps/web run build`
- Contract validation: `npm --prefix packages/api-contracts test`

## Structure

See [README.md](README.md) and [docs/architecture/container-architecture.md](docs/architecture/container-architecture.md).

## Remaining MVP work

1. Add authentication, organization/project authorization, and persistent project/configuration APIs.
2. Add feature revision storage and validation without executing content in the API.
3. Implement queue-backed run state transitions and idempotent run creation.
4. Build and review the hardened Docker runner: non-root user, read-only filesystem, dropped capabilities, resource/time limits, network policy, and explicit artifact egress.
5. Parse runner output into structured feature/scenario/step results and persist immutable run snapshots.
6. Add encrypted secret-provider integration and redaction tests.
7. Add SSE event publication, artifact upload, run history, dashboard, and accessible editor UI.
8. Add Testcontainers integration, Karate API dogfooding, accessibility checks, and end-to-end tests.

## Security concerns

The runner is not production-ready and must not be connected to arbitrary user content. Before enabling execution, complete the threat-model controls in `docs/security/threat-model.md`, perform dependency/image scanning, define network egress policy, validate tenant isolation, and add hostile-input/container escape tests.

## Recommended next slice

Implement project and feature-revision persistence plus authenticated, authorization-checked API contracts. Keep run creation as a persisted `CREATED` record only; do not launch Karate until the hardened runner contract and security review are complete.

## Validation note

This bootstrap was created in an environment without Java, Gradle, or Docker binaries. Node.js is available; dependency installation/build validation should be run in CI or a development environment with the prerequisites listed above.
