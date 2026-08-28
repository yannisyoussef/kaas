# KaaS Independent Architecture Review

Review date: 2026-08-27
Reviewer role: independent Principal Software Engineer, Security Engineer, Quality Architect, and Platform Engineer
Reviewed commit: `dcf3507b76b11f465c5d67fe80b6baf777c62586`

## 1. Executive Summary

KaaS has the right headline direction: a monorepo, a modular control plane, a separate execution plane, asynchronous runs, structured results, and an explicit warning not to execute hostile user content before sandbox controls exist. That direction is appropriate for an MVP and avoids premature microservices and Kubernetes.

The repository is not yet a dependable architecture bootstrap. Most boundaries exist only in prose and diagrams. The backend is an application class plus an unconditional health controller; the runner prints one line and does not consume a command; there is no domain, application, persistence, messaging, authentication, result, secret, or observability implementation. The checked-in CI is red, the documented Gradle wrapper does not exist, the build script fails under the available current Gradle, the documented contract test command has no package manifest, the lint command is interactive, and the production frontend dependency graph contains known high-severity advisories.

The contracts and threat model are the most consequential design weaknesses. The OpenAPI file is not sufficient to generate or implement clients and fails a recommended linter because it defines no security. The runner result leaves scenarios opaque and has no step model. Messaging reliability, state concurrency, cancellation, retries, trace propagation, secret capabilities, artifact integrity, and sandbox-launcher trust are not resolved. Twelve ADRs repeat nearly identical boilerplate instead of recording decision-specific alternatives and tradeoffs.

No P0 is assigned because arbitrary test execution is disabled and the documentation explicitly gates it. Enabling execution with the present design would change that assessment.

| Dimension | Score | Explanation |
|---|---:|---|
| Architecture maturity | 4/10 | Sound top-level direction, but boundaries and invariants are not instantiated and key contracts conflict or remain incomplete. |
| Security readiness | 3/10 | The trust boundary and major threats are named, and unsafe execution is disabled; enforcement designs and residual-risk treatment are missing. |
| Test strategy | 1/10 | Two placeholder tests, no contract harness, no frontend tests, interactive lint, and a backend job that never starts. |
| Developer experience | 3/10 | The layout and basic documentation are approachable; Compose config and web build work, but primary documented backend and contract commands fail. |
| Portfolio strength | 4/10 | The intended story is strong, but red CI, placeholder tests, vulnerable dependencies, copied ADRs, and implementation overclaims would dominate an interview review. |

Overall verdict: **REQUIRES ARCHITECTURAL CORRECTIONS FIRST**.

## 2. Repository State

- Branch: `main`.
- Default/base branch: `origin/main`, resolved through `origin/HEAD`.
- Base comparison: `HEAD` and `origin/main` both resolve to `dcf3507`; `git diff origin/main...HEAD` is empty.
- Commit reviewed: `dcf3507b76b11f465c5d67fe80b6baf777c62586`, “Bootstrap KaaS: architecture, contracts, docs, and app skeletons.”
- Working tree before review: no tracked changes; pre-existing untracked `.idea/` directory.
- Working tree after verification and before creating this report: no tracked changes; pre-existing untracked `.idea/` remained. Next.js temporarily rewrote `apps/web/tsconfig.json` and `apps/web/next-env.d.ts` during the lint prompt; both were restored byte-for-byte to `HEAD` before this report was written.
- Intentional review output: this report only. No source, configuration, dependency, CI, or documentation file was changed.

Repository structure at the reviewed commit:

```text
.
├── apps
│   ├── api                 # Spring Boot application + one context test
│   └── web                 # Next.js landing/dashboard scaffold
├── services
│   └── runner              # Java main that prints a bootstrap message
├── packages
│   └── api-contracts       # Three JSON Schemas + prose; no test package
├── infrastructure
│   ├── local               # PostgreSQL/RabbitMQ/Redis/MinIO Compose file
│   └── observability       # Three-line target statement
├── docs
│   ├── adr                 # 12 bootstrap ADRs
│   ├── api                 # 47-line OpenAPI document
│   ├── architecture
│   ├── product
│   └── security
├── .github/workflows       # One two-job CI workflow
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── IMPLEMENTATION_STATUS.md
```

There is no prior commit or divergent feature branch to review. This is the repository's initial commit.

## 3. Verification Performed

| Command | Result | Notes |
|---|---|---|
| `git branch --show-current` | PASS | `main`. |
| `git status --short --branch` | PASS | `main...origin/main`; only pre-existing untracked `.idea/` before the report. |
| `git branch -a -vv` / `git symbolic-ref refs/remotes/origin/HEAD` | PASS | Local `main`, `origin/main`, and `origin/HEAD` all identify the same commit. |
| `git log --oneline --decorate -12` / `git rev-parse HEAD` | PASS | One commit, full SHA recorded above. |
| `git diff --stat origin/main...HEAD` / `git diff --name-status origin/main...HEAD` | PASS | No base-branch diff. |
| `git diff --check` | PASS | No tracked whitespace errors. |
| `rg --files -uu ...` and file-by-file reads | PASS | All 52 tracked files were inventoried and reviewed. |
| `java -version`, `node --version`, `npm --version`, `docker --version`, `docker compose version` | PASS | Java 25.0.3, Node 22.18.0, npm 10.9.3, Docker 29.5.2, Compose 5.1.4. No system `gradle` command. |
| `./gradlew clean test` | FAIL | Exit 127: `./gradlew` does not exist. |
| Cached Gradle 9.6.1: `gradle clean test --offline` | FAIL | Root `build.gradle.kts:4`: core plugin `java` requested with `apply false`; Gradle rejects it as a no-op. Compilation/tests never began. |
| Cached Gradle 8.14.5: `gradle help --offline` | INCONCLUSIVE | Could not run on the installed Java 25 runtime. This does not mitigate the absent wrapper or current-Gradle failure. |
| `docker compose -f infrastructure/local/docker-compose.yml config` | PASS | Compose is syntactically valid and expands four services/two volumes. Containers were not started. |
| Initial sandboxed `npm --prefix apps/web ci --ignore-scripts` | INCONCLUSIVE | No output for 60 seconds; interrupted with exit 130 because registry access was restricted. |
| `npm --prefix apps/web ci --ignore-scripts --no-audit --no-fund` with registry access | PASS | 28 packages installed; npm warned that Next.js 14.2.30 has a security vulnerability. |
| Initial `npm --prefix apps/web run build` before successful install | FAIL | `next: command not found`; environmental consequence of the interrupted install. |
| `npm --prefix apps/web run build` after `npm ci` | PASS | Production compile, TypeScript check, static generation, and route optimization completed. |
| `CI=true npm --prefix apps/web run lint` | FAIL | Opened interactive ESLint configuration because ESLint is not configured. It also rewrote TypeScript files, which were restored. This would not be a stable non-interactive CI check. |
| `npm --prefix apps/web test` | FAIL | No `test` script. |
| `npm --prefix apps/web audit --omit=dev --audit-level=moderate` | FAIL | Two high-severity production findings: `next` and transitive `postcss`. |
| `npm --prefix packages/api-contracts test` | FAIL | Exit 254: `packages/api-contracts/package.json` does not exist. |
| Python `json.loads` over the three schemas | PASS | All three files are syntactically valid JSON. PyYAML was not installed, so that YAML-only check was not performed. |
| `npx @redocly/cli lint docs/api/openapi-v1.yaml` | FAIL | Five errors and 12 warnings; all five operations lack a security definition, the Problem Detail component is unused, and operations lack stable IDs/error responses. |
| `npx ajv-cli compile --spec=draft2020 ...` without `ajv-formats` | TOOLING FAIL | Validator did not recognize `uuid`; this was a validator setup issue, not evidence of invalid schemas. |
| `npx --package=ajv-cli@5.0.0 --package=ajv-formats@3.0.1 ajv compile --spec=draft2020 -c ajv-formats ...` | PASS | All three JSON Schemas compile under Draft 2020-12 with standard formats enabled. |
| `npm --prefix apps/web ls --all --depth=1` | PASS | Installed graph matches the lockfile; platform-inapplicable optional SWC packages are expectedly unmet. |
| `gh run list --limit 5 ...` / `gh run view 33123144573 --json jobs...` | PASS after network access | The only CI run for `dcf3507` failed. Web passed; backend failed at `./gradlew test`. |
| `gh run view 33123144573 --log-failed` | PASS | Confirmed backend exit 127: `./gradlew: No such file or directory`. |
| Final `git diff --exit-code` | PASS before report | Confirmed verification left all pre-existing tracked files unchanged. |

No claim below treats an unexecuted test as passing.

## 4. Architecture Assessment

The proposed topology is reasonable for the MVP: one deployable control-plane application, one worker boundary, one ephemeral runtime per run, PostgreSQL for authoritative data, object storage for artifact bytes, RabbitMQ for work delivery, and SSE for one-way progress. The separation is more important than whether every port or adapter exists on day one.

The current repository is a design sketch, not a modular monolith bootstrap. There are no control-plane modules, ports, adapters, domain types, messaging boundaries, persistence models, or runner APIs to inspect. That is acceptable only if the documentation says “designed only” and the next work begins by validating the design. Several documents instead use present tense (“owns,” “enforced,” “transports,” “stores,” “consumes”), and `IMPLEMENTATION_STATUS.md` labels nonexistent behavior as created.

The design is strongest where it separates test failure from infrastructure failure and explicitly forbids execution until security controls pass. It is weakest where distributed execution becomes real: lifecycle concurrency, message redelivery, result identity/integrity, secret delivery, network policy, artifact egress, and sandbox launcher privileges.

## 5. What Was Done Well

- `README.md:5` and `IMPLEMENTATION_STATUS.md:72` explicitly prohibit user-controlled test execution before the execution plane is hardened. That prevents the present gaps from becoming an immediate P0.
- `docs/architecture/container-architecture.md:3-25` selects a pragmatic modular-monolith/control-plane shape and avoids Kubernetes for the MVP.
- `docs/architecture/execution-lifecycle.md:10-17` distinguishes assertion failure (`TEST_FAILED`) from untrustworthy infrastructure failure. This is a necessary product invariant.
- `docs/architecture/domain-model.md:18` correctly treats run inputs as snapshots, result records as append-only after completion, and artifact bytes as object-storage data rather than database blobs.
- `docs/product/mvp-scope.md:7-13` contains useful scope discipline and a clear execution release gate.
- `docs/architecture/security-boundaries.md` names identity, execution, worker/sandbox, secret, tenant, and artifact trust boundaries rather than treating Docker as sufficient by itself.
- The three JSON Schemas use Draft 2020-12, stable `$id` values, and a `contractVersion: v1` discriminator; all compile with AJV plus standard format support.
- `docs/api/openapi-v1.yaml:18-25` uses `202 Accepted` for asynchronous run creation and requires an idempotency key.
- `apps/web` is deliberately small, uses strict TypeScript, has no unnecessary state framework, and produces a successful optimized build.
- `infrastructure/local/docker-compose.yml` validates successfully and limits itself to local dependencies rather than introducing Kubernetes.

## 6. Findings

### P0 Blockers

None. Execution is disabled, so the incomplete sandbox cannot currently process hostile user code. This classification depends on preserving that gate.

### P1 High

#### KAA-001

- **ID:** KAA-001
- **Severity:** P1 — High
- **Area:** Build reproducibility / CI
- **Location:** `README.md:41-48`; `IMPLEMENTATION_STATUS.md:35-53`; `.github/workflows/ci.yml:15-22`; `build.gradle.kts:1-5`
- **Evidence:** Every documented and CI backend command calls `./gradlew`, but the repository contains no wrapper script, wrapper JAR, or wrapper properties. The only GitHub Actions run failed with exit 127 at that command. Running the build with cached Gradle 9.6.1 then failed at `id("java") apply false` before project configuration completed.
- **Impact:** No developer or CI runner can reproduce the backend build from the repository. Backend and runner compilation and tests are unverified, making all later work unsafe to merge.
- **Recommendation:** Choose a supported Gradle version, correct the root plugin declaration, generate and commit the complete wrapper, and make `./gradlew clean check` the authoritative local/CI path. Add wrapper validation and dependency verification.
- **Fix before:** Any feature implementation or acceptance of this bootstrap.

#### KAA-002

- **ID:** KAA-002
- **Severity:** P1 — High
- **Area:** API contract / authentication
- **Location:** `docs/api/openapi-v1.yaml:8-47`; `packages/api-contracts/README.md:5-15`
- **Evidence:** The OpenAPI operations define descriptions but no request bodies, success response bodies, pagination schemas, content types, security schemes, security requirements, or referenced Problem Details responses. Redocly reported five security-definition errors and 12 warnings. The prose claims run-profile input, idempotent replay behavior, error fields, and pagination that the OpenAPI does not express.
- **Impact:** The claimed API-first foundation cannot drive implementation, client generation, authorization review, or contract tests. Implementers will invent incompatible DTOs and error semantics.
- **Recommendation:** Define authentication and organization/project authorization, complete request/response schemas and headers, reference RFC 9457 Problem Details responses, specify pagination/filtering, document same-key replay status/body semantics, add cancellation, and lint/bundle the spec in CI.
- **Fix before:** Implementing project, feature, environment, or run endpoints.

#### KAA-003

- **ID:** KAA-003
- **Severity:** P1 — High
- **Area:** Execution and messaging contracts
- **Location:** `packages/api-contracts/runner-command.schema.json:1`; `packages/api-contracts/runner-result.schema.json:1`; `packages/api-contracts/live-event.schema.json:1`; `packages/api-contracts/README.md:17-19`
- **Evidence:** Commands have no command/message ID, attempt, absolute deadline, causation/correlation/trace context, immutable content digest, or explicit reply/result protocol. Secret references are untyped strings. Results use `EXECUTION_FAILED` while the lifecycle uses `INFRASTRUCTURE_ERROR`; scenario objects are completely unconstrained and there is no step schema. Result and nested feature/artifact objects permit undeclared fields, errors are unstructured strings, and artifacts have no size or digest. Live-event payloads are arbitrary objects and no resume/gap semantics accompany `sequence`.
- **Impact:** Consumers cannot implement safe idempotency, redelivery, observability, result validation, artifact integrity, or backward compatibility. “Structured results” currently stop at feature level.
- **Recommendation:** Define separate command, lifecycle-event, execution-result, and artifact-manifest envelopes with stable IDs, attempt/deadline, trace context, tenant/run identity, schema version, and strict payloads. Use one canonical state/outcome vocabulary; model feature/scenario/step results and structured infrastructure errors; specify duplicate, ordering, acknowledgement, retry, DLQ, and compatibility rules.
- **Fix before:** Implementing RabbitMQ publishing/consumption or any runner execution.

#### KAA-004

- **ID:** KAA-004
- **Severity:** P1 — High
- **Area:** Hostile-code security architecture
- **Location:** `docs/adr/006-docker-runner.md`; `docs/architecture/container-architecture.md:13-23`; `docs/architecture/security-boundaries.md:5-8`; `docs/security/threat-model.md:11-28`
- **Evidence:** The documents list desired controls but do not decide how the worker launches containers without exposing a privileged Docker socket, which component owns the Docker daemon, what credentials the sandbox receives, how egress allowlists resist DNS rebinding/private IP resolution, or how artifacts reach storage without broad write credentials. The model does not state the residual risk of container escape or the fact that a test legitimately given a secret can exfiltrate it through an allowed target; redaction cannot prevent that. No concrete runtime profile, image provenance, seccomp/AppArmor policy, user namespace, rootless strategy, cleanup guarantee, quota hierarchy, or control-verification test exists.
- **Impact:** A future implementation could satisfy the prose superficially while retaining host takeover, lateral movement, metadata-service access, cross-tenant artifact writes, or secret exfiltration paths.
- **Recommendation:** Replace the control catalog with an enforceable execution security design: launcher trust and Docker access, sandbox identity, rootless/non-root runtime, immutable image digest/provenance, capabilities/syscalls/namespaces, read-only mounts, bounded tmpfs, cgroups, wall-clock watchdog outside the sandbox, destination-aware egress, per-run secret capabilities, worker-mediated or narrowly scoped artifact upload, cleanup/failure behavior, and hostile validation tests. Record accepted residual risk.
- **Fix before:** Building the launcher or allowing any untrusted feature to execute.

#### KAA-005

- **ID:** KAA-005
- **Severity:** P1 — High
- **Area:** Testing / quality engineering credibility
- **Location:** `apps/api/src/test/java/com/kaas/api/KaasApiApplicationTests.java:6-10`; `services/runner/src/test/java/com/kaas/runner/RunnerApplicationTest.java:6-10`; `apps/web/package.json:4`; `IMPLEMENTATION_STATUS.md:48-53`
- **Evidence:** The API test only loads a context. The runner “security” test is `assertTrue(true)`. The web package has no test script, and lint launches an interactive setup. The documented contract test points to a directory with no `package.json`. There are no domain, state-machine, repository, API, architecture, security, messaging, accessibility, integration, concurrency, or negative tests.
- **Impact:** The repository does not demonstrate the testing discipline expected from a Quality Engineering portfolio and cannot prevent architectural drift.
- **Recommendation:** After restoring the build, add a small high-value foundation: ArchUnit module/boundary tests, state transition table tests, schema positive/negative fixtures, API contract tests, real runner no-execution/command-validation tests, deterministic lint/typecheck, and CI-enforced test reports. Add Testcontainers only when a real adapter exists.
- **Fix before:** Major feature development; state/contract tests must precede run orchestration.

#### KAA-006

- **ID:** KAA-006
- **Severity:** P1 — High
- **Area:** Dependency security
- **Location:** `apps/web/package.json:5`; `apps/web/package-lock.json:9,310-368`
- **Evidence:** `npm ci` warns that `next@14.2.30` is vulnerable. A live `npm audit --omit=dev` reports two high-severity production findings covering Next.js and transitive PostCSS, including SSRF, denial-of-service, cache, request-smuggling, XSS, and file-disclosure advisories. The current static pages reduce exposure to several advisories but do not make a known-vulnerable production framework an acceptable baseline.
- **Impact:** New server components, middleware, image optimization, rewrites, or user-controlled CSS/content could make vulnerable paths reachable. The initial lockfile already fails a reasonable security gate.
- **Recommendation:** Select a supported patched Next.js line compatible with the intended deployment, regenerate the lockfile, rerun build/tests/audit, and add automated dependency review with an explicit triage policy. Do not blindly run a force upgrade.
- **Fix before:** Deploying the web application or adding dynamic/user-controlled features.

#### KAA-007

- **ID:** KAA-007
- **Severity:** P1 — High
- **Area:** Run lifecycle / concurrency
- **Location:** `docs/architecture/execution-lifecycle.md:3-20`; `docs/adr/009-state-machine.md`
- **Evidence:** Cancellation is defined only from `CREATED` and `QUEUED`; provisioning, running, and result processing cannot be cancelled. Timeout exists only from `RUNNING`; queue/provision/result-processing deadlines are absent. Worker-loss detection, leases/heartbeats, retries, duplicate terminal results, optimistic concurrency, transition ownership, cancellation races, and reconciliation are undefined. “Monotonic, persisted, audited, and emitted as ordered events” is asserted without a storage or concurrency model.
- **Impact:** At-least-once delivery and concurrent cancellation/result messages can corrupt state or leave runs permanently nonterminal. Retry behavior could accidentally rerun tests or overwrite trustworthy results.
- **Recommendation:** Define a transition table with actor, precondition, version check, side effects, terminality, timeout, retryability, and idempotency for every edge. Separate lifecycle state from test outcome, infrastructure outcome, and quality-gate outcome. Define leases/reconciliation and transactional outbox/inbox behavior before choosing topology details.
- **Fix before:** Persisting or publishing runs.

### P2 Medium

#### KAA-008

- **ID:** KAA-008
- **Severity:** P2 — Medium
- **Area:** Documentation accuracy
- **Location:** `README.md:27-35`; `IMPLEMENTATION_STATUS.md:8-25`; `docs/architecture/container-architecture.md:3`
- **Evidence:** Documentation says the boundary “is enforced by contracts,” the runner “consumes only a typed command,” the API has a “Problem Details-style error shape,” RabbitMQ is behind an application port, and PostgreSQL/RabbitMQ/object storage perform runtime roles. None exists in code. The runner only prints a line, ProblemDetail appears only as an unused OpenAPI schema, and there are no ports/adapters.
- **Impact:** Reviewers and contributors cannot distinguish verified foundations from design intent. For a portfolio, this looks like completion inflation.
- **Recommendation:** Label each material item as implemented, validated, scaffolded, designed, or planned. Use future tense for topology not present in code and link each implemented claim to an executable test.
- **Fix before:** Publishing or presenting the repository; update alongside KAA-001 through KAA-005.

#### KAA-009

- **ID:** KAA-009
- **Severity:** P2 — Medium
- **Area:** ADR quality
- **Location:** `docs/adr/001-monorepo.md` through `docs/adr/012-opentelemetry.md`
- **Evidence:** All 12 ADRs repeat the same context, alternatives, advantages, disadvantages, and consequences. For example, the PostgreSQL ADR does not compare persistence choices, the RabbitMQ ADR does not discuss delivery semantics, the Docker ADR does not compare isolation models, and the SSE ADR does not address reconnection/replay/proxy behavior.
- **Impact:** The records do not preserve why decisions were made, which constraints matter, or when to revisit them. They cannot guide implementation or defend tradeoffs in review.
- **Recommendation:** Keep only decisions needed now and rewrite each with decision-specific context, considered alternatives, rejected reasons, operational/security consequences, validation criteria, and supersession triggers. Mark uncommitted choices “Proposed,” not “Accepted for bootstrap.”
- **Fix before:** Implementing the component governed by each ADR.

#### KAA-010

- **ID:** KAA-010
- **Severity:** P2 — Medium
- **Area:** Health and operability
- **Location:** `apps/api/src/main/java/com/kaas/api/HealthController.java:7-13`; `apps/api/build.gradle.kts:7-9`
- **Evidence:** A custom controller declares `/actuator/health` and always returns `{"status":"UP"}` while Spring Boot Actuator is also installed. It can bypass or compete with the real health endpoint and can never report dependency or readiness failure.
- **Impact:** Orchestrators and operators may route traffic to an unhealthy API, and the path becomes ambiguous as real health contributors are added.
- **Recommendation:** Remove the custom mapping; configure Actuator liveness/readiness groups and add only meaningful health contributors. Test status behavior without exposing sensitive component detail.
- **Fix before:** Adding database/broker dependencies or deployment health checks.

#### KAA-011

- **ID:** KAA-011
- **Severity:** P2 — Medium
- **Area:** Local infrastructure / developer experience
- **Location:** `infrastructure/local/docker-compose.yml:1-26`; `.env.example:1-5`; `README.md:39-48`
- **Evidence:** Compose validates but has no health checks, startup dependencies, application services, network segmentation, or environment-file interpolation. All service ports bind on all host interfaces. PostgreSQL and MinIO use fixed credentials, RabbitMQ implicitly uses its default local credentials, and Redis has no authentication. Image tags are mutable rather than digest-pinned. The `.env.example` values are not consumed by Compose or application configuration.
- **Impact:** Local startup is nondeterministic, services can be exposed to the local network, and the documented `.env` step gives a false sense of configuration. Mutable images reduce reproducibility.
- **Recommendation:** Bind development ports to `127.0.0.1`, source local credentials/config from a noncommitted env file with explicit safe defaults, add health checks, document startup verification, remove unused services, and pin reviewed image versions/digests where reproducibility matters.
- **Fix before:** Onboarding additional developers or adding integration tests.

#### KAA-012

- **ID:** KAA-012
- **Severity:** P2 — Medium
- **Area:** Persistence / domain invariants
- **Location:** `docs/architecture/domain-model.md`; `docs/adr/004-postgresql.md`; `docs/adr/011-structured-results.md`
- **Evidence:** The diagram names aggregates but provides no identity/scope rules, uniqueness, foreign keys, timestamp semantics, deletion policy, optimistic versioning, immutable snapshot representation, index/query needs, artifact integrity fields, retention, or transactional relationship between run state and published messages. No schema or migration exists.
- **Impact:** The next implementation can accidentally leak tenants, mutate historical evidence, lose commands between database commit and publish, or create result queries that cannot scale.
- **Recommendation:** Before migrations, document aggregate invariants and a minimal logical schema: tenant-scoped keys, revision immutability, run input snapshot, separate outcomes, append-only results, artifact size/digest/content type, version column, audit timestamps, retention, and outbox/inbox records. Add constraints rather than relying only on services.
- **Fix before:** The first persistence migration.

#### KAA-013

- **ID:** KAA-013
- **Severity:** P2 — Medium
- **Area:** Observability
- **Location:** `docs/adr/012-opentelemetry.md`; `infrastructure/observability/README.md`; all runner/event contracts
- **Evidence:** Observability is a three-line target and a generic ADR. There is no logging configuration, trace propagation field, metric inventory, semantic convention, cardinality policy, event correlation rule, or local collector. The execution command cannot carry trace context.
- **Impact:** HTTP-to-run-to-queue-to-worker causality will be retrofitted after contracts harden, and IDs may be placed in high-cardinality metric labels.
- **Recommendation:** Define a minimal observability contract now: `traceId` propagation through message headers, structured log keys (`traceId`, `runId`, `projectId`, `messageId`), low-cardinality metric dimensions, queue/startup/execution timers, failure counters, and redaction rules. Implement instrumentation with the first real run flow.
- **Fix before:** Finalizing message envelopes; instrumentation may follow with the first run slice.

#### KAA-014

- **ID:** KAA-014
- **Severity:** P2 — Medium
- **Area:** Supply-chain controls
- **Location:** `docs/security/threat-model.md:24`; `.github/workflows/ci.yml`; Gradle files; `apps/web/package-lock.json`
- **Evidence:** The threat model claims lockfiles, SBOM, vulnerability scanning, provenance, and review as controls. Only the npm lockfile exists. There is no Gradle locking or verification metadata, SBOM task, dependency/image scan, dependency-review workflow, artifact provenance, or pinned action SHA.
- **Impact:** Documented mitigations are not operating controls, and backend/container dependency changes are not reproducibly reviewed.
- **Recommendation:** Add Gradle dependency locking and checksum verification, SBOM generation, npm audit/dependency review with triage, container scanning when images exist, least-privilege workflow permissions, and reviewed action pinning policy.
- **Fix before:** Calling the security control implemented or publishing deployable artifacts.

#### KAA-015

- **ID:** KAA-015
- **Severity:** P2 — Medium
- **Area:** Quality gates / result semantics
- **Location:** `docs/architecture/domain-model.md:3-18`; `docs/product/product-vision.md:13-15`; `packages/api-contracts/runner-result.schema.json:1`
- **Evidence:** The domain model and result contract contain no quality-gate definition or evaluation. There is no explicit place to represent “tests passed, quality gate failed” independently from run state and test/infrastructure outcomes.
- **Impact:** A later quality-gate feature may overload `FAILED`, destroy outcome meaning, or couple policy evaluation to Karate execution.
- **Recommendation:** Keep quality evaluation in the control plane and define it as a separate, versioned evaluation over immutable results. Reserve independent fields/types now without implementing a full rules engine.
- **Fix before:** Freezing the run/result API or adding quality policy behavior.

### P3 Low

#### KAA-016

- **ID:** KAA-016
- **Severity:** P3 — Low
- **Area:** Unnecessary bootstrap artifacts
- **Location:** `infrastructure/local/docker-compose.yml:13-15`; `services/runner/build.gradle.kts:1-4`; `.gitmodules`
- **Evidence:** Redis is deployed although documentation calls it optional and no use case exists. Jackson is a runner dependency but runner code does not parse a command. `.gitmodules` exists only to state that there are no submodules.
- **Impact:** Small dependency/operational noise and misleading signals that capabilities exist.
- **Recommendation:** Remove unused artifacts until a concrete adapter/use case needs them. Reintroduce with tests and an ADR consequence when justified.
- **Fix before:** Routine cleanup before the next slice; not independently blocking.

#### KAA-017

- **ID:** KAA-017
- **Severity:** P3 — Low
- **Area:** Frontend foundation
- **Location:** `apps/web/app/layout.tsx`; `apps/web/app/page.tsx`; `apps/web/app/dashboard/page.tsx`; `apps/web/package.json`
- **Evidence:** The scaffold builds and uses server components appropriately, but has no metadata, API client/error convention, route loading/error boundaries, component tests, or accessibility automation. The landing page states that safe asynchronous execution and structured results exist, while the dashboard is placeholder text.
- **Impact:** Minor now, but presentation overstates capability and no convention exists when real data arrives.
- **Recommendation:** Keep the scaffold simple. Add metadata and honest copy now; introduce typed API/data/error/loading conventions and accessibility/component tests only with the first data-backed page.
- **Fix before:** Public portfolio presentation or the first interactive page.

#### KAA-018

- **ID:** KAA-018
- **Severity:** P3 — Low
- **Area:** Repository hygiene
- **Location:** `.gitignore:1-7`; untracked `.idea/`
- **Evidence:** `.idea/` is present and untracked but not ignored.
- **Impact:** Easy accidental IDE metadata commits and a permanently noisy working tree.
- **Recommendation:** Ignore workspace-specific IDE state or commit only deliberately shared IDE configuration under a documented policy.
- **Fix before:** The next commit.

## 7. Security Review

The repository shows awareness of the right threat families, but almost every mitigation is currently aspirational. “Execution disabled” is the only strong implemented control.

| Threat | Current mitigation | Gap | Residual risk | Recommendation |
|---|---|---|---|---|
| User code in API | API has no Karate runtime; runner also executes nothing. | No architecture test prevents future Karate/process dependencies entering the API. | Future convenience code could collapse the trust boundary. | Add dependency/package rules and a security release gate. |
| Container escape / host takeover | Planned non-root, dropped capabilities, read-only root, seccomp/AppArmor review. | No image, runtime profile, launcher, daemon isolation, or Docker-socket decision. | Containers share a kernel; a runtime/kernel flaw can escape. | Rootless isolated daemon/host, reviewed syscall/capability profile, patched digest-pinned images, no socket in sandbox, explicit accepted risk. |
| SSRF / internal probing | Planned egress policy, DNS/IP controls, proxy. | No policy location, DNS rebinding defense, redirect handling, IPv4/IPv6/private-range rules, or per-target authorization. | Allowed HTTP testing inherently creates network reachability. | Enforce destination-aware egress outside the sandbox and test metadata/private/DNS-rebinding cases. |
| Secret theft/exfiltration | References, runtime injection, redaction are planned. | References are untyped; injection mechanism and access scope are undefined. Redaction does not stop deliberate exfiltration by code that receives the secret. | A legitimate target may still leak a secret; logs/reports/artifacts can encode it. | Per-run/per-target least-privilege capabilities, short TTL, egress restriction, multi-encoding redaction tests, audit, clear residual-risk statement. |
| Tenant IDOR | Server-side authorization and negative tests are planned. | No auth/security scheme, tenant-aware type, repository rule, or test exists. | First endpoints can accidentally accept user-supplied ownership scope. | Derive tenant from authenticated principal; scope every key/query; test cross-tenant IDs and object keys. |
| Resource exhaustion | Planned CPU/memory/PID/output/time limits. | No queue quotas, concurrency policy, external watchdog, disk/tmpfs/log/artifact bounds, or cleanup behavior. | Queue, worker, host disk, broker, database, or object storage can be exhausted. | Layer admission quotas, cgroups, bounded tmpfs/output, host-enforced deadline, concurrency limits, cleanup/reconciliation. |
| Command injection / traversal | Abuse cases mention metacharacters and malicious payloads. | No command construction/workspace design or typed safe path rules. | Shell invocation, archive extraction, feature URI, and artifact paths can escape workspace. | Avoid shell strings, use fixed argv, canonicalize paths, reject traversal/symlinks/archives, adversarial tests. |
| Poison messages / duplicates | Schema validation and idempotent consumers are planned. | No message ID, attempt, inbox, retry/DLQ topology, acknowledgement rule, or size cap. | Duplicate execution, poison loops, lost terminal results. | Versioned envelope, validate before ack, bounded retries/DLQ, inbox dedupe, reconciliation. |
| Result/artifact tampering | Planned immutable snapshots and restricted writes. | No digest/size/producer identity; diagram grants sandbox-to-storage flow without scoped credential design. | Cross-run overwrite, malicious HTML, forged result, storage abuse. | Worker-mediated upload or run-scoped presigned capability; hash/size/type manifest; immutable object keys; authorized download; quarantine/sanitize. |
| XSS through reports/logs | Content disposition, CSP, sanitization planned. | No artifact serving design or browser policy. | HTML reports are attacker-controlled active content. | Download by default from isolated origin; strict CSP/sandbox; never inline into main application origin. |
| Dependency/image compromise | npm lockfile; desired scanning/provenance in threat model. | Current production advisories; no backend lock/checksums, scan, SBOM, digest, or provenance. | Compromised/vulnerable build or runtime dependency. | Apply KAA-006 and KAA-014 with explicit triage and provenance policy. |
| API abuse / rate bypass | Threat prompt expected it; repository does not model it. | No authentication, quotas, size limits, rate-limit key, or proxy trust model. | Run creation can become a cost/resource amplification endpoint. | Principal/project quotas, idempotency-bound charging, trusted-forwarded-header policy, payload limits, abuse telemetry. |

Security readiness means readiness to design the next safe slice, not readiness to execute hostile tests. The latter is effectively 0/10 today and should remain disabled.

## 8. Control Plane Review

There is no control-plane architecture in code to evaluate beyond the Spring Boot entry point and health controller. No controller/service/domain leakage exists because no business feature exists. Equally, no modular-monolith boundary, dependency direction, validation, transaction, exception handling, authentication, authorization, persistence adapter, mapping, or use-case pattern has been proven.

The next control-plane work should use a simple vertical module (`project` with feature revisions) split only where it creates testable dependency direction: API adapter → application use case → domain/invariants → repository port, with persistence/auth adapters at the edge. Avoid generic base controllers/services/repositories and avoid creating empty “domain” types for every diagram box.

Problem Details should be centralized and contract-backed. JPA entities should not be API DTOs. Transactions should encompass aggregate changes and outbox writes, not remote broker/object-storage calls.

## 9. Execution Plane Review

The separate `services/runner` location is directionally correct, but it is not a worker or contract consumer. It has no broker client, command type, schema validation, launcher port, workspace preparation, executor, result parser, cleanup, or response path. Jackson is unused.

Future runner implementations could coexist if the control plane publishes engine-neutral execution intent and the selected engine/version is explicit at the execution boundary. It is reasonable for the first worker adapter to be Karate-specific; the control-plane run lifecycle, artifact model, and quality-gate evaluation should not encode Karate HTML or exit-code quirks.

The most important next design is not “run Karate.” It is a no-op execution-plane protocol proving validated command receipt, idempotent claim, lease/heartbeat, state ownership, isolated launcher invocation, bounded completion, structured result acceptance, and reconciliation. Only after KAA-004 is resolved should that launcher accept feature content.

## 10. Domain Model Review

`Project` and `TestRun` are plausible aggregate roots, and immutable `FeatureRevision` is a strong reproducibility choice. The documentation does not yet define invariants. At minimum:

- Organization scope must be part of every aggregate lookup and uniqueness rule.
- A feature revision is immutable, content-addressed or digest-protected, and belongs to exactly one logical feature/project.
- A run snapshots revision, target, resolved non-secret configuration, secret reference versions, profile/environment identity, engine version, and policy—not live mutable records.
- Run lifecycle state, test outcome, infrastructure outcome, and quality-gate evaluation are separate concepts.
- Terminal raw results are immutable; processing may append a versioned derived interpretation without rewriting evidence.
- Artifact metadata includes opaque key, tenant/run ownership, type, size, digest, creation time, retention, and scan state.

User and Organization relationships, audit principals, deletion/retention, and environment-variable precedence require decisions before persistence. Avoid DDD ceremony: records/value objects plus explicit transition and authorization policies are enough for the MVP.

## 11. API Review

Using `/api/v1`, project-scoped run creation, `202 Accepted`, UUID identifiers, and an idempotency header is sensible. The contract is otherwise only an endpoint index.

Run creation should return `202` with a run representation and `Location`; a repeated identical idempotency key should return the original representation under documented semantics, while a different fingerprint should return a Problem Detail conflict. Cancellation needs an idempotent operation and response semantics for already-terminal/racing runs. GET collections need bounded cursor or page semantics and stable sorts. SSE needs authorization, `Last-Event-ID`/resume behavior, heartbeats, retention/gap behavior, disconnect cleanup, and an event schema per type.

The health endpoint should not be placed under versioned product APIs, but the custom actuator override should be removed as described in KAA-010.

## 12. Persistence Review

There is no database driver, migration tool, entity, repository, or schema. PostgreSQL is a reasonable choice, but ADR-004 does not justify it beyond a sentence.

The first migration should not attempt the full domain. It should prove tenant-scoped projects, immutable feature revisions, audit timestamps/principals, constraints, indexes for actual queries, and optimistic versioning where concurrent mutation exists. A later run slice should add an outbox/inbox with the run record so a committed run cannot be lost before queue publication. Structured feature/scenario/step results belong in relational tables or carefully justified JSON with query requirements documented; report/log bytes belong in object storage.

## 13. Messaging Review

RabbitMQ is present only as a Compose image and ADR sentence. There is no port, topology, publisher, consumer, acknowledgement, retry, DLQ, idempotency, ordering, or transaction design. Exactly-once delivery must not be assumed.

Use at-least-once semantics: transactional outbox for commands/events, message IDs, inbox/deduplication at consumers, manual acknowledgement after durable state/result handling, bounded retry for transient infrastructure faults, DLQ for poison/permanent failures, and reconciliation for expired leases. Do not rely on broker ordering for correctness; enforce run-version transitions in PostgreSQL. Keep exchange/queue names and Rabbit-specific headers out of the domain.

## 14. Frontend Review

The App Router scaffold correctly defaults to server components and avoids needless client state. It builds successfully. There is no API/data interaction, so client strategy, error handling, loading states, authentication, and state management cannot yet be assessed.

Do not add a global state library preemptively. Generate or hand-maintain a small typed API client from the corrected OpenAPI, use route-level server fetching where appropriate, introduce client components only for interactive editors/live streams, and add accessible loading/error/empty states with the first real page. Monaco can wait until feature revision workflows justify it. The current marketing copy should be explicit that functionality is planned.

## 15. Observability Review

OpenTelemetry is only a declared target. Define context propagation in the message envelope before it becomes a compatibility contract. Logs should be structured and include stable correlation fields while excluding secret values and feature payloads. Metrics should use bounded labels such as outcome/engine/queue—not `runId`, `projectId`, feature, scenario, or tenant.

Useful initial measurements are HTTP run acceptance latency, queue duration, provisioning duration, execution duration, result-processing duration, active/leased runners, terminal outcomes by bounded category, retries, DLQ count, cancellation latency, and artifact bytes rejected. Traces must cross HTTP → outbox/publish → consumer → launcher → result processing using standard propagation headers.

## 16. Testing Review

The current tests do not validate behavior. `assertTrue(true)` is especially damaging in a Quality Engineering portfolio because its name implies a security property it cannot prove.

Recommended test layers, added only as functionality appears:

1. Pure domain tests for state transitions, snapshots, idempotency fingerprints, and quality-gate separation.
2. Contract compilation plus positive/negative message fixtures and backward-compatibility checks.
3. API slice/integration tests for validation, status codes, Problem Details, tenant IDOR, pagination, and idempotency.
4. PostgreSQL/RabbitMQ Testcontainers tests for constraints, outbox/inbox, duplicates, redelivery, cancellation races, and worker loss.
5. Runner security tests for command/path handling, resource/output limits, egress, secret redaction, cleanup, and crash at every transition.
6. Frontend component/accessibility tests and a minimal browser flow when real UI exists.
7. Karate dogfooding against KaaS's public API only after the public API runs; do not make KaaS execute those tests inside its API process.

## 17. CI/CD Review

The current CI is not a foundation: its backend job fails before build configuration, and its web job only installs and builds. It does not run explicit lint, frontend tests, contract validation, OpenAPI lint, Compose validation, dependency review/audit, architecture tests, SBOM, or container validation. There are no job timeouts, minimal workflow permissions, concurrency cancellation, or artifact/test reports.

The web build does type-check and succeeds, but this does not replace a configured lint/test/security pipeline. Fix the wrapper/build first, then make CI stages explicit and locally reproducible. Do not add container scanning until a project-owned image exists.

## 18. Documentation / ADR Review

Product vision and scope are concise and mostly appropriate. The most valuable documentation is the explicit security release gate. The weakest is the ADR set: twelve files create the appearance of decision rigor without decision-specific analysis.

ADR statuses should reflect reality. Monorepo, Java/Spring, and repository layout are implemented. PostgreSQL/RabbitMQ/S3/SSE/state machine/secrets/structured results/OpenTelemetry are proposed designs. Docker-per-run is a security-sensitive proposal pending launcher and isolation validation, not an accepted safe boundary.

Documentation should never use “enforced,” “stores,” “transports,” or “consumes” for code that does not exist. A short capability matrix in the README would be more honest than broad present-tense architecture claims.

## 19. Documentation Drift

| Claim | Documentation | Implementation | Status |
|---|---|---|---|
| Monorepo with API/web/runner/contracts/docs | README layout; ADR-001 | Directories and build modules exist. | IMPLEMENTED |
| Modular monolith with module boundaries | ADR-002; container architecture | One flat API package; no modules or boundary tests. | DESIGNED ONLY |
| API has health and Problem Details error shape | `IMPLEMENTATION_STATUS.md:11` | Custom unconditional health exists; no error handler/response implementation. | INCONSISTENT |
| Runner consumes a typed command safely | `IMPLEMENTATION_STATUS.md:12` | Runner only prints a message; no command type or consumer. | INCONSISTENT |
| Boundary is enforced by contracts | `README.md:27` | Schemas exist, but no component imports, validates, or tests them. | DESIGNED ONLY |
| RabbitMQ is hidden behind an application port | `IMPLEMENTATION_STATUS.md:21` | No messaging code or port exists. | NOT IMPLEMENTED |
| PostgreSQL stores authoritative metadata/results | Container architecture | Compose service only; no driver, schema, migration, or repository. | DESIGNED ONLY |
| Docker runner is ephemeral and isolated | ADR-006/security docs | No runner image, launcher, or runtime configuration. | NOT IMPLEMENTED |
| State transitions are persisted/audited/ordered | Execution lifecycle | Diagram only; no state model or persistence. | DESIGNED ONLY |
| Structured feature/scenario/step results | ADR-011/contracts README | Feature schema exists; scenarios are arbitrary objects; steps absent. | PARTIALLY IMPLEMENTED |
| Secrets are external references and never logged/returned | Implementation status/security docs | Plain-string reference field in schema; no provider, storage, injection, or redaction. | DESIGNED ONLY |
| SSE live events | ADR-007/OpenAPI/schema | Endpoint and generic event are documented; no implementation or replay semantics. | DESIGNED ONLY |
| OpenTelemetry traces/metrics/logs | ADR-012/observability README | No dependency, config, instrumentation, propagation, or collector. | NOT IMPLEMENTED |
| Local PostgreSQL/RabbitMQ/Redis/MinIO | README/implementation status | Compose definitions exist and validate; startup/health not verified. | PARTIALLY IMPLEMENTED |
| GitHub Actions validation workflow | Implementation status | Workflow exists, web passes, overall CI/backend fails. | INCONSISTENT |
| Contract validation command | `IMPLEMENTATION_STATUS.md:53` | No package manifest/test command. | NOT IMPLEMENTED |
| Quality decisions are traceable | Product vision | No quality-gate model or evaluation. | DESIGNED ONLY |

## 20. Overengineering Assessment

- Twelve “accepted” ADRs are too many when most choices are not implemented and the records contain copy-pasted reasoning. Fewer, real decisions would be stronger.
- Redis is running in local Compose without a justified use case. Remove it until transient distributed state is actually needed.
- `.gitmodules` exists to state there are no submodules; absence of the file communicates that already.
- Jackson is present in the runner without any parsing code. Add it when a validated message adapter exists.
- Do not create ports/factories/interfaces for every future diagram box. Establish boundaries through one real vertical slice and extract only meaningful infrastructure seams.

## 21. Underengineering Assessment

- Reproducible Gradle wrapper/build and green CI.
- Machine-enforced OpenAPI and JSON Schema validation.
- Concrete authentication/authorization and tenant-scope rules.
- Explicit run transition/concurrency/retry/cancellation model.
- Enforceable hostile-code sandbox/launcher/security design with residual risks.
- Usable execution/message/result/artifact contracts.
- Minimal relational invariants, migrations, and outbox/inbox strategy.
- Real domain/API/contract/security/architecture tests.
- Dependency locking/verification, audit, SBOM, and image provenance policy.
- Trace/log/metric correlation contract.
- Local service health checks and honest setup verification.
- Separate quality-gate evaluation concept.

## 22. Portfolio Assessment

### What would a Senior/Staff QA Engineer interviewer challenge here?

1. “Why is the only runner security test `assertTrue(true)`?”
2. “Why was a bootstrap committed with red CI and no Gradle wrapper when the README tells me to run it?”
3. “Show me how the API contract produces a request/response model, authentication requirement, or Problem Detail.”
4. “How do you handle RabbitMQ redelivery, duplicate commands, cancellation races, worker death, and lost result messages?”
5. “Where is the step-level structured result promised by ADR-011?”
6. “How does a worker launch Docker without becoming a host-root remote-control service?”
7. “How do you stop a Karate test from exfiltrating a secret to an otherwise allowed target?”
8. “Why should I trust 12 ADRs whose alternatives and consequences are identical?”
9. “Which security controls are implemented versus future intent, and why does the threat model call absent controls current controls?”
10. “Why does local infrastructure expose unauthenticated/default-credential services beyond loopback?”
11. “What does `PASSED` mean if a quality gate fails, and where is that represented?”
12. “How can the health endpoint always return UP once the database and broker become dependencies?”

The strongest interview response is not to add more diagrams. It is to correct the build/contracts, replace placeholder tests with a few adversarial executable proofs, and present documentation that is precise about what is not yet implemented.

## 23. Recommended Corrections

Order follows dependency, not severity alone.

- **R1 — Restore a reproducible baseline.** Correct `build.gradle.kts`, commit a pinned Gradle wrapper, make clean backend/runner checks pass, configure non-interactive lint, and make CI green.
- **R2 — Remediate and govern dependencies.** Move Next.js/PostCSS to a supported patched line, add Gradle locking/checksum verification, and establish audit/SBOM/dependency-review gates.
- **R3 — Correct the architecture record.** Rewrite only the ADRs needed for the next two slices; mark future choices Proposed; replace present-tense implementation claims with a capability/status matrix.
- **R4 — Resolve cross-cutting semantics.** Finalize the four separate dimensions (lifecycle, test outcome, infrastructure outcome, quality-gate evaluation) and the transition/concurrency/retry/cancellation table.
- **R5 — Resolve the hostile execution design.** Decide launcher/daemon trust, network policy, secret capabilities, artifact egress, image/runtime profile, quotas, watchdog, cleanup, and residual-risk acceptance before runner code.
- **R6 — Repair and automate contracts.** Complete OpenAPI security/request/response/errors; redesign strict message/result/event envelopes; add positive/negative fixtures, OpenAPI lint, AJV compile, and compatibility checks to CI.
- **R7 — Establish the first control-plane module foundation.** Define tenant-scoped identities/invariants, migrations, auth integration, Problem Details, audit fields, and a narrow port/adapter structure with tests.
- **R8 — Make local infrastructure support that slice.** Remove unused services, bind loopback, add health checks/config interpolation, and add only the integration dependencies the slice uses.
- **R9 — Add observability with real behavior.** Instrument the first persisted/API flow using the correlation/log/metric rules fixed in the contracts; defer runner dashboards until a runner exists.
- **R10 — Reassess before execution.** Perform a focused threat-model and hostile-runner review; execution remains disabled until all release-gate controls have executable evidence.

## 24. Recommended Next Development Slice

After R1-R7, implement exactly one vertical slice: **authenticated, organization-scoped Project creation/retrieval plus immutable FeatureRevision creation/retrieval in PostgreSQL**.

The slice includes complete OpenAPI schemas, JWT-derived organization identity, server-side authorization, Flyway migrations with tenant/uniqueness/immutability constraints, application/domain/repository boundaries, RFC 9457 errors, audit fields, integration tests with PostgreSQL, cross-tenant negative tests, and trace/log correlation. It excludes run creation, queues, Redis, MinIO, SSE, secrets, and all Karate execution.

This slice proves the modular-monolith, API-first, tenant-security, persistence, test, CI, and observability foundations without crossing the unsafe execution boundary.

## 25. Final Verdict

**REQUIRES ARCHITECTURAL CORRECTIONS FIRST**

The repository should not progress directly into feature implementation. Restore a green reproducible build, make the contracts implementable, replace copied ADRs with real decisions, and close the lifecycle/security design gaps. Preserve the current execution-disabled gate throughout.

Finding totals:

- P0 Blocker: 0
- P1 High: 7
- P2 Medium: 8
- P3 Low: 3
