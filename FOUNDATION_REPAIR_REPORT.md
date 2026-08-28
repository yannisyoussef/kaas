# KaaS Foundation Repair Report

Repair date: 2026-08-27
Baseline commit: `dcf3507b76b11f465c5d67fe80b6baf777c62586`
Scope: foundation repair only; no business functionality or test execution

## 1. Changes made

### Reproducible JVM foundation

- Changed the project toolchain target from Java 21 to **Java 25** as explicitly directed.
- Selected **Gradle 9.7.1**, the newest stable patch release on the repair date. Gradle 9.8 was available only as a milestone and was not selected.
- Selected **Spring Boot 4.1.1**, which explicitly supports Java through 26 and Gradle 9.x.
- Removed the invalid core `java` plugin declaration with `apply false` and configured `JavaPluginExtension` explicitly for every JVM subproject.
- Generated the complete Gradle wrapper (`gradlew`, `gradlew.bat`, wrapper JAR, and properties).
- Pinned the Gradle binary distribution SHA-256 in wrapper properties.
- Verified the wrapper JAR SHA-256 as `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` and the binary distribution SHA-256 as `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` against Gradle's published checksums.

Version rationale was checked against the [Gradle 9.7.1 release notes](https://docs.gradle.org/9.7.1/release-notes.html), [Gradle Java compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html), and [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

### Meaningful backend and runner verification

- Removed the custom unconditional `/actuator/health` controller.
- Enabled only Actuator health plus liveness/readiness foundations; no database or broker contributor is pretended.
- Replaced the API context-only assertion with two behaviors: application startup on a random port and HTTP verification of health/liveness/readiness responses.
- Removed the runner's `assertTrue(true)` placeholder.
- Refactored the runner bootstrap so its non-execution behavior is exercised through an injected `PrintStream`, without global output mutation or external process execution.
- Added the explicit JUnit Platform launcher runtime required by Gradle 9.7 for the standalone runner module.
- Removed unused Jackson from the runner.

### Frontend foundation

- Upgraded unsupported/vulnerable Next.js 14.2.30 to **Next.js 16.3.3 Active LTS**, the patched August 2026 security release.
- Upgraded React/React DOM to 19.2.8 and Node type definitions to the Node 24 line.
- Standardized frontend and contract tooling on **Node.js 24 LTS**; Node 20 is end-of-life.
- Added deterministic ESLint 10 flat configuration using the direct Next.js plugin and current TypeScript ESLint. This avoids both the removed `next lint` command and obsolete/incompatible transitive presets.
- Added explicit `lint`, `typecheck`, `test`, and `build` scripts.
- Added a small server-render test that checks semantic main/heading content, honest execution-disabled copy, and named dashboard navigation.
- Added `next typegen` before TypeScript checking so a clean checkout does not rely on pre-existing `.next` types.
- Scoped Turbopack to the web package so unrelated lockfiles outside the repository do not influence root detection.
- Recorded npm 11 install-script approvals only for locked `esbuild@0.28.2` and optional `fsevents@2.3.3`.
- Regenerated the npm lockfile and verified zero production vulnerabilities.

Version selection used the [Next.js support policy](https://nextjs.org/support-policy), [August 2026 security release](https://nextjs.org/blog), and [Node.js release schedule](https://nodejs.org/en/about/previous-releases).

### Contract tooling

- Turned `packages/api-contracts` into a real private npm package with pinned AJV, AJV Formats, and Redocly CLI dependencies.
- Added deterministic Draft 2020-12 compilation of all three JSON Schemas.
- Added one positive and one negative fixture per schema.
- Added one `npm test` command that validates schemas/fixtures and lints OpenAPI.
- Made the draft OpenAPI structurally lint-clean by adding operation IDs, proposed bearer security, reusable Problem Details responses, a relative server URL, and license metadata.
- Did not redesign runner message/result semantics; KAA-003 remains deferred.

### CI and infrastructure

- Replaced the failing two-job workflow with backend, web, contracts, and infrastructure jobs.
- Added explicit `contents: read` permissions and bounded timeouts.
- Updated to current stable action majors and verified that every referenced tag exists.
- Configured CI for Temurin 25, Node 24, Gradle wrapper validation/caching, explicit frontend checks, schema/OpenAPI validation, production npm audit, and Compose config validation.
- Removed Redis because no current use case exists.
- Bound PostgreSQL, RabbitMQ, and MinIO ports to `127.0.0.1`.
- Made local Compose overrides explicit through `.env.example` with safe development defaults.
- Added basic health checks and explicit RabbitMQ local credentials.
- Removed the meaningless `.gitmodules` file.
- Added `.idea/`, `*.iml`, local env variants, and TypeScript build info to `.gitignore`.

## 2. Findings resolved

| Finding | Resolution |
|---|---|
| KAA-001 — build reproducibility / CI | RESOLVED. Complete checksum-pinned Gradle 9.7.1 wrapper; Java 25; current Spring Boot; clean backend/runner checks; meaningful CI. |
| KAA-006 — vulnerable frontend dependencies | RESOLVED. Next.js 16.3.3/React 19.2.8 lockfile; `npm audit --omit=dev` reports zero vulnerabilities. |
| KAA-008 — documentation accuracy | RESOLVED for the current foundation. Capability matrix and status vocabulary distinguish implemented, validated, scaffolded, designed, and planned behavior. Architecture drafts are explicitly labeled. |
| KAA-009 — ADR quality | RESOLVED for active foundation decisions. Five decision-specific ADRs remain; seven boilerplate/future ADRs were removed and classified as deferred topics. |
| KAA-010 — health endpoint | RESOLVED. Custom endpoint removed; real Actuator health/liveness/readiness tested over HTTP. |
| KAA-016 — unnecessary artifacts | RESOLVED. Redis, Jackson, and `.gitmodules` removed. |
| KAA-018 — repository hygiene | RESOLVED. IDE state and generated TypeScript build info are ignored. |

Contract tooling also repairs the nonexistent contract test command and creates executable evidence for the next architecture iteration. It does not claim the draft contracts are semantically complete.

## 3. Findings intentionally deferred

- **KAA-002:** Product-level OpenAPI request/response, pagination, authorization, cancellation, and idempotency semantics. This repair only made the draft structurally lintable.
- **KAA-003:** Execution/message/result/event envelope redesign, reliability, step-level structure, artifact integrity, and trace context.
- **KAA-004:** Hostile-code launcher, daemon/socket, network, secret, resource, artifact, cleanup, and residual-risk design.
- **KAA-005:** Full domain/API/integration/security/accessibility test strategy. Placeholder tests were removed and foundation behaviors are real, but product test layers must follow product code.
- **KAA-007:** Run state-machine concurrency, leases, retries, cancellation, timeouts, reconciliation, and outcome separation.
- **KAA-011:** Runtime validation of local container health. Compose configuration validates, but the local Docker daemon was unavailable during the bounded startup attempt.
- **KAA-012 through KAA-015:** Persistence invariants, observability semantics, broader supply-chain controls, and quality-gate modeling remain future architecture work.

## 4. Dependency changes

### JVM

| Dependency/tool | Before | After |
|---|---:|---:|
| Java toolchain | 21 | 25 |
| Gradle wrapper | absent | 9.7.1 |
| Spring Boot | 3.4.5 | 4.1.1 |
| Jackson in runner | 2.18.3, unused | removed |
| JUnit runner setup | JUnit Jupiter only | JUnit 5.11.4 BOM/Jupiter + Platform launcher |

### Web

| Dependency/tool | Before | After |
|---|---:|---:|
| Node baseline | 20+ documented | 24 LTS |
| Next.js | 14.2.30 | 16.3.3 |
| React / React DOM | 18.3.1 | 19.2.8 |
| TypeScript | 5.8.3 | 5.9.3 |
| ESLint | absent/interactively prompted | 10.9.1 flat config |
| TypeScript ESLint | absent | 8.68.0 |
| Next ESLint plugin | implicit/unconfigured | 16.3.3 direct plugin |
| TSX test loader | absent | 4.23.12 |

### Contract tooling

- `ajv` 8.20.0
- `ajv-formats` 3.0.1
- `@redocly/cli` 2.49.0

All npm versions are exact and lockfiles are committed as repair outputs.

## 5. ADR changes

Retained and rewritten:

- ADR-001 — monorepo — **IMPLEMENTED**
- ADR-002 — modular control plane / separate execution boundary — **PROPOSED**
- ADR-003 — Java 25 / Spring Boot 4.1.1 / Gradle 9.7.1 — **IMPLEMENTED**
- ADR-004 — PostgreSQL persistence — **PROPOSED**
- ADR-006 — per-run Docker isolation candidate — **PROPOSED; NOT APPROVED FOR UNTRUSTED EXECUTION**

Removed as boilerplate and classified as deferred topics in the ADR index:

- RabbitMQ topology
- SSE semantics
- Object storage
- Run state machine
- Secret management
- Structured results
- OpenTelemetry

Each retained ADR now has decision-specific context, decision, alternatives, rejection rationale, advantages, disadvantages, consequences, and validation/revisit conditions.

## 6. Documentation changes

- README now leads with repository reality, not target product behavior.
- Added a concise capability/status matrix with explicit evidence/boundaries.
- Updated toolchain and all commands for Java 25, Gradle 9.7.1, Node 24, and the repaired packages.
- Rewrote `IMPLEMENTATION_STATUS.md` around implemented/validated, scaffolded, designed/proposed, and planned/absent capabilities.
- Marked architecture, lifecycle, domain, security-boundary, and threat-model documents as proposed/draft/requirements rather than implemented controls.
- Updated contract documentation to state that schemas are scaffolded and no runtime component consumes them.
- Preserved the execution-disabled security gate throughout.

## 7. Verification commands and results

Final verification used Temurin Java 25.0.3, Gradle 9.7.1, and a checksum-verified temporary Node.js 24.20.0 LTS runtime.

| Command | Result | Exact outcome |
|---|---|---|
| `./gradlew --version` | PASS | Gradle 9.7.1; launcher/daemon JVM Temurin 25.0.3. |
| `./gradlew clean check` | PASS | Build successful; API 2 tests passed; runner 1 test passed; 0 failures/errors. |
| `npm --prefix apps/web ci` | PASS | 137 packages installed/audited; 0 vulnerabilities. |
| `npm --prefix apps/web run lint` | PASS | ESLint completed with `--max-warnings=0`; no output violations. |
| `npm --prefix apps/web run typecheck` | PASS | Next route types generated; TypeScript completed without errors. |
| `npm --prefix apps/web test` | PASS | 1 semantic server-render test passed; 0 failed/skipped. |
| `npm --prefix apps/web run build` | PASS | Next.js 16.3.3 Turbopack production build; `/`, `/_not-found`, and `/dashboard` generated statically. |
| `npm --prefix apps/web audit --omit=dev` | PASS | `found 0 vulnerabilities`. |
| `npm --prefix packages/api-contracts ci` | PASS | 7 packages installed; 0 vulnerabilities. |
| `npm --prefix packages/api-contracts run validate:schemas` | PASS | Three schemas compiled; all three positive and three negative fixtures behaved as expected. |
| `npm --prefix packages/api-contracts run lint:openapi` | PASS | Redocly recommended lint: API description valid with no warnings. |
| `npm --prefix packages/api-contracts test` | PASS | Combined schema/fixture and OpenAPI validation passed. |
| `docker compose -f infrastructure/local/docker-compose.yml config` | PASS | Three services, loopback bindings, credentials, health checks, networks, and volumes expand successfully. |
| `docker compose -f infrastructure/local/docker-compose.yml up -d --wait` | ENVIRONMENT BLOCKED | Docker daemon was not running; no containers were created. Runtime service health is not claimed. |
| `git diff --check` | PASS | No whitespace errors. |
| GitHub Action tag checks via `gh api` | PASS | `actions/checkout@v6`, `actions/setup-java@v5`, `actions/setup-node@v6`, and `gradle/actions@v6` all resolve. |

During repair, the first Gradle 9.7 check correctly failed because the standalone runner lacked the JUnit Platform launcher. The dependency was added explicitly, and both subsequent clean checks passed. The failure is resolved, not omitted.

## 8. Remaining architectural issues

- OpenAPI and runner schemas are structurally validated but not semantically ready for implementation.
- Authentication in OpenAPI is proposed contract structure only; no authentication code exists.
- The modular-monolith boundary has not been proven by a business vertical slice or architecture test.
- PostgreSQL, RabbitMQ, and MinIO are not connected to either application.
- State/outcome/quality-gate semantics are not finalized.
- At-least-once messaging, outbox/inbox, retries, DLQ, cancellation, leases, and reconciliation are not designed.
- Docker is not approved as a sufficient hostile-code boundary; no runner image or launcher exists.
- Secret capability, network egress, artifact integrity, and telemetry contracts remain undefined.
- Broader Gradle dependency locking/verification metadata, SBOM, and image scanning remain outside this repair's requested findings.

## 9. Exact recommendation for the next architecture iteration

Perform one **contract and run-lifecycle architecture iteration** before product or execution implementation.

That iteration should produce:

1. A canonical separation of lifecycle state, test outcome, infrastructure outcome, and quality-gate evaluation.
2. A complete transition table covering actor ownership, valid/invalid edges, optimistic concurrency, cancellation races, queue/provision/execution/result timeouts, leases, retries, reconciliation, and terminality.
3. Strict versioned command/result/event/artifact envelopes with message/causation/correlation IDs, attempt, absolute deadline, trace context, tenant/run identity, content digest, structured errors, and artifact integrity metadata.
4. Full feature/scenario/step result schemas and positive/negative/compatibility fixtures.
5. Explicit at-least-once delivery, acknowledgement, inbox/outbox, retry, DLQ, duplicate, and ordering semantics without RabbitMQ implementation.
6. SSE resume/gap/heartbeat/authorization semantics without SSE implementation.
7. Updated Proposed ADRs and machine-validated contracts.

Do not implement RabbitMQ, persistence, authentication, Docker launching, secrets, or Karate during that architecture iteration.

## Final foundation status

**GREEN FOR THE NEXT ARCHITECTURE ITERATION.**

The repository now has a reproducible Java 25/Gradle 9.7.1 build, meaningful baseline tests, a patched frontend, deterministic contract tooling, structurally meaningful CI, honest documentation, cleaned ADRs, and safer local Compose definitions. It is not ready for arbitrary execution or product feature implementation.
