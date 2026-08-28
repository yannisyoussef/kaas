# KaaS Contract and Run-Lifecycle Architecture Report

Architecture date: 2026-08-27
Foundation: Java 25, Gradle 9.7.1, Spring Boot 4.1.1, Node.js 24 LTS
Scope: design plus machine-validated contracts; no runtime product behavior or arbitrary execution

## 1. Executive summary

This iteration establishes clear, transport-neutral semantics for KaaS runs and four strict machine-validated contracts: immutable execution command, structured execution result, artifact manifest, and low-volume live event. It also completes the proposed run-focused OpenAPI contract and records four actual decisions as Proposed ADRs.

The central correction is an orthogonal model: lifecycle tracks orchestration; cancellation tracks user intent/effect; test outcome, infrastructure outcome, and quality-gate evaluation carry independent meanings. Every durable mutation uses optimistic `runVersion`; worker ownership uses an additional assignment epoch to fence stale processes. At-least-once delivery, not exactly-once execution, is the explicit reliability assumption.

The repository remains deliberately non-functional as a product. It contains no run endpoint implementation, database, RabbitMQ adapter, authentication, SSE server, secret provider, object-storage adapter, Karate dependency, container launcher, or arbitrary execution path.

## 2. Decisions made

1. One terminal lifecycle state (`COMPLETED`) plus orthogonal cancellation/test/infrastructure/quality dimensions.
2. Explicit `COLLECTING_RESULTS`, `PROCESSING_RESULTS`, and `STOPPING` phases so result upload, processing, cancellation, and timeout behavior are observable.
3. Compare-and-set on `runVersion` for every durable transition; assignment epoch for lease fencing; no distributed locks.
4. Separate run from execution attempt; exactly one infrastructure attempt in the MVP and no automatic infrastructure retry.
5. At-least-once command/result/manifest delivery with transactional outbox, consumer inbox, stable IDs/digests, post-commit acknowledgement, and reconciliation.
6. Immutable engine/source/config/environment/policy snapshots in execution commands; no mutable runtime references where reproducibility matters.
7. Structured feature/scenario/test-attempt/step evidence, separate test and infrastructure errors, and separately verified artifact manifests.
8. Quality evaluation owned only by the control plane and versioned independently from raw evidence.
9. Five durable, bounded SSE event kinds with finite replay, explicit gap semantics, unsequenced heartbeats, and terminal close.
10. Conservative compatibility: optional fields and enum additions are breaking for existing closed/exhaustive consumers unless a coordinated version rollout proves otherwise.

These decisions are recorded in ADR-007, ADR-009, ADR-011, and ADR-013 with Proposed status because runtime evidence does not exist.

## 3. Run lifecycle model

The lifecycle is:

`CREATED → QUEUED → CLAIMED → PROVISIONING → RUNNING → COLLECTING_RESULTS → PROCESSING_RESULTS → COMPLETED`

Active cancellation, phase timeout, or lease loss moves an owned execution phase to `STOPPING`, then `COMPLETED`. Early cancellation and queue timeout complete directly. Result-processing timeout completes with infrastructure failure after incomplete derived data is quarantined.

Conceptual actors are API, scheduler, worker gateway, result processor, reconciler, and quality evaluator. They are responsibility labels, not microservices. The complete transition table in `docs/architecture/run-state-machine.md` records current/event/result, actor, preconditions, expected version, durable effects, emitted events, idempotency, timeout/terminal behavior, and invalid/race handling.

## 4. Outcome model

| Dimension | Values |
|---|---|
| Lifecycle | `CREATED`, `QUEUED`, `CLAIMED`, `PROVISIONING`, `RUNNING`, `COLLECTING_RESULTS`, `PROCESSING_RESULTS`, `STOPPING`, `COMPLETED` |
| Cancellation | `NOT_REQUESTED`, `REQUESTED`, `ACKNOWLEDGED` |
| Test outcome | `PASSED`, `FAILED`, `NOT_AVAILABLE` |
| Infrastructure outcome | `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` |
| Quality gate | `PASSED`, `FAILED`, `NOT_EVALUATED` |

`SUCCEEDED` requires complete trustworthy test evidence and test outcome `PASSED` or `FAILED`. For the MVP, non-success infrastructure makes canonical test outcome `NOT_AVAILABLE`; partial evidence may be diagnostic but cannot drive a gate. Terminal canonical outcomes and raw evidence are immutable. Quality evaluation is a separately versioned derived record.

## 5. Cancellation semantics

The proposed API is `POST /api/v1/runs/{runId}/cancellations`; cancellation does not delete the run.

- `CREATED`/`QUEUED`: cancellation can complete immediately (`200`).
- `CLAIMED` through `COLLECTING_RESULTS`: persist request, fence new work, emit one stop command, enter `STOPPING`, and return `202`.
- `PROCESSING_RESULTS`: cancellation is too late because evidence is already durably accepted; return RFC 9457-style 409.
- `COMPLETED`: an already acknowledged cancellation replays its stable response; other terminal outcomes return lifecycle conflict.

The first valid run-version compare-and-set wins. Accepted result first means cancellation loses; cancellation or timeout first makes a later ordinary result stale. Repeated requests are naturally run-idempotent and additionally use scoped `Idempotency-Key` semantics.

## 6. Timeout, lease, and reconciliation semantics

Initial contract defaults are queue wait 5 minutes, 30-second lease with 10-second heartbeat and 30-second recovery window, provisioning 2 minutes, execution default 5 minutes/range 1 second–1 hour, result collection 2 minutes, result processing 2 minutes, and stop grace 30 seconds.

Deadlines are absolute and server-controlled. Heartbeats renew only an exact active attempt/epoch and do not change `runVersion` or public lifecycle. Reconciliation checks phase deadlines, conditionally fences an expired epoch, emits stop/cleanup intent, and terminalizes after grace. Late heartbeat/result/manifest data from a fenced epoch is stale. No automatic retry follows worker loss in the MVP.

Runtime implementation must still define clock-skew tolerance and prove launcher cleanup; terminal database state alone cannot stop a partitioned sandbox.

## 7. Messaging reliability model

KaaS assumes at-least-once delivery. A domain mutation and immutable outbox record commit atomically. The publisher may republish after a crash. Consumers commit inbox identity/digest, conditional domain effects, and outgoing outbox rows before broker acknowledgment. A crash after commit/before ACK produces expected redelivery and a deduplicated no-op.

Exact ID+digest duplicates are acknowledged without repeating effects. Same identity with different bytes/digest, cross-tenant substitution, wrong epoch, or forged metadata is quarantined and audited. Permanent validation/version/poison messages do not loop through transient retries. Broker ordering is irrelevant to lifecycle correctness.

Command, lifecycle event, execution result, artifact manifest, and public live event remain distinct closed contracts. No RabbitMQ topology names are selected.

## 8. Idempotency model

| Boundary | Key | Duplicate | Conflict |
|---|---|---|---|
| Run creation | Authenticated org/principal/operation/project + `Idempotency-Key` + fingerprint | Original status/body/Location/ETag | 409 for different fingerprint |
| Publication | Stable outbox/message ID + digest | Republish identical message | Halt/security alert on changed digest |
| Consumption | Consumer + message ID inbox | ACK committed disposition | Quarantine identity/digest conflict |
| Claim | Attempt + assignment epoch | Return existing lease | Reject competing/stale assignment |
| Result | Result/message ID + digest + command/attempt/epoch | Existing canonical disposition | Quarantine conflicting evidence |
| Artifact | Artifact/manifest/reservation + size/digest | No-op identical registration | Reject/quarantine mismatch |
| Cancellation | One intent per run + scoped API key | Current/original response | 409 key or lifecycle conflict |

Idempotency storage is a conceptual persistence requirement only; no tables exist.

## 9. Retry model

- API client retry is transparent through idempotency.
- Message delivery retry is transparent to the domain and bounded to transient failures.
- Infrastructure execution retry is visible, always creates a new attempt/epoch/command, and is disabled for the MVP.
- Karate scenario retry stays inside one execution attempt and is recorded in `scenario.attempts[]`.

Assertion failures are never automatically retried by orchestration. A user rerun creates a new run for now. DLQ classification explicitly separates transient exhaustion, permanent validation, unsupported version, poison handler behavior, stale no-op, and integrity/security conflict.

## 10. Execution command contract

`EXECUTION_COMMAND` is an immutable Draft 2020-12 closed schema. It contains message/command identities, tenant/project/run/version, attempt/assignment epoch, correlation/causation, producer/time/deadline, exact engine version, immutable source bundle and SHA-256, per-feature revision/digest/logical path, resolved non-secret scalar configuration, opaque non-authorizing secret references, environment snapshot, selection, parallelism, scenario retry, timeout, bounded artifact policy, and immutable network-policy identity/version/digest.

It contains no raw secrets, provider paths/tokens, host paths, URLs, Docker settings, shell commands, bucket credentials, or arbitrary configuration JSON. `traceparent`/`tracestate` are transport metadata, not business payload.

## 11. Structured result contract

`EXECUTION_RESULT` is a strict message/evidence envelope bound to command/run/attempt/epoch. It separates test outcome, infrastructure outcome, and completeness; records provisioning/execution/reporting durations; provides recomputable nested summaries; and contains feature → logical scenario → scenario test attempts → ordered step evidence.

Outline rows use stable definition/block/row identity plus source digest without copying example values. Backgrounds and hooks are explicit. Test errors and infrastructure errors are separate bounded sanitized shapes; diagnostic detail is an opaque reference. Quality-gate fields and unknown runtime internals are rejected.

The validator adds semantic checks JSON Schema cannot express: chronology, cross-contract identity/epoch binding, final-summary recomputation, uniqueness, contiguous test-retry numbering, final-attempt status, progress, and aggregate fixture policy.

## 12. Artifact contract

`ARTIFACT_MANIFEST` is a separate strict message contract bound to command/run/attempt/epoch. Each artifact requires ID, allowlisted type, constrained content type, bounded size, exact stored-byte lowercase SHA-256, opaque control-plane `object-ref:`, and creation time. It carries no bytes, URL, bucket name, arbitrary key, or credential.

Receipt is not availability. A future control plane must verify reservation, storage-observed size/digest, aggregate command policy, type sniffing, and scan/quarantine before registration/event emission. HTML and `OTHER` remain hostile download-only content by default.

## 13. Live event and SSE semantics

The durable event set is `RUN_STATE_CHANGED`, `EXECUTION_STARTED`, coalesced `PROGRESS`, verified `ARTIFACT_AVAILABLE`, and canonical `EXECUTION_COMPLETED`. Per-step, per-scenario, and raw-log events were excluded to control amplification and replay risk.

Per-run sequence is durable and becomes decimal SSE `id`; UUID `eventId` supports deduplication. `Last-Event-ID` resumes strictly after a bounded validated cursor. Heartbeats are 15-second comment frames with no sequence. Initial proposed retention is 24 hours after terminal completion; an older cursor receives 410 Problem Details with safe resync information. Terminal event is delivered and the stream closes; a post-terminal cursor may receive 204. Connection duration is proposed at 15 minutes to force reauthorization. Disconnect never cancels a run.

Bearer auth supports fetch-based clients; a proposed same-site Secure HttpOnly cookie supports native EventSource. Query tokens are forbidden. No authentication/SSE implementation exists.

## 14. Quality-gate separation

The minimum `QualityGateEvaluation` has status, independent evaluation version, evaluation time, policy version, and bounded typed checks (`name`, `actual`, operator, `expected`, status). It is not a rule engine. It is computed only by the control plane from accepted immutable results and may be reevaluated without changing run lifecycle/outcomes/evidence.

## 15. Observability propagation

HTTP and messaging use W3C `traceparent`/optional `tracestate`; messaging carries them as technical headers. Trace context is never authorization or idempotency data. Structured logs may contain trusted trace/span, project/run/attempt/message IDs and bounded states/outcome/error codes, but never secrets, test inputs/text/tags/payloads, host paths, object references, URLs, stack traces, or artifact/log contents.

Metrics may label bounded service/operation/message type/schema major/lifecycle/outcome/error category/HTTP status/engine/worker pool. They must not label any tenant/project/run/attempt/message/command/result/artifact ID, trace ID, feature/scenario/step/tag/path/URL/object reference, secret reference, or policy version.

## 16. Security review

The focused review addressed replay, tampering, stale work, cross-tenant substitution, forged result/manifest, traversal, durable secret leakage, host-path leakage, oversized evidence/logs, malicious HTML, version confusion, and message amplification.

Contract-level mitigations include authenticated-producer requirement; authoritative identity lookup and full tuple comparison; run version plus assignment fencing; stable ID/digest conflict detection; closed allowlisted schemas; opaque non-authorizing references; normalized logical paths; pre-parse aggregate limits; structured sanitized errors; verified artifact bytes; finite SSE event budget; and quarantine/security audit for integrity conflicts.

Three independent adversarial reviews were performed:

- Distributed-systems/API: added collecting/stopping phases, explicit point-of-no-return, assignment fencing, stable cancellation/results semantics, and finite SSE gap handling.
- Quality Engineering/schema: added result completeness, nested test-retry evidence, deterministic outlines, hook/background modeling, separated errors, semantic assertions, and conservative closed-schema compatibility.
- Security/observability: removed durable step/log SSE events, added authenticated binding and epoch checks, opaque object references, outcome matrix, aggregate limits, browser SSE auth constraints, and metric-cardinality prohibitions.

These contracts do not replace runtime sandbox, egress, resource, secret, malware, storage, or tenant isolation.

## 17. Compatibility and versioning policy

Consumers select only locally allowlisted exact schema/type combinations; no remote producer-selected schema. Description-only changes are compatible. Looser bounds are conditionally compatible only while writers honor old consumers. Under closed strict schemas, optional property additions and enum additions are breaking to old validators/exhaustive consumers. Required/removal/rename/tightening/meaning/identity/digest/order changes are breaking.

Breaking evolution uses a new version and dual-read/new-write rollout: deploy readers, switch writers, drain old messages for their maximum lifetime, then retire old readers. No general schema registry is built.

## 18. Files and contracts changed

- Added canonical architecture: `run-semantics.md`, `run-state-machine.md`, `execution-protocol.md`, `messaging-reliability.md`, `result-model.md`, `live-events.md`, and `observability-contract.md`.
- Replaced the obsolete lifecycle diagram with a pointer to canonical documents; updated domain model and threat model.
- Added/reworked Proposed ADR-007, ADR-009, ADR-011, and ADR-013 plus the ADR index.
- Redesigned execution command, execution result, and live-event schemas; added artifact-manifest schema.
- Added 36 fixtures: 11 valid and 25 isolated negative cases across four major contracts.
- Extended contract validation with schema compilation, expected AJV failure categories, and named semantic invariants.
- Replaced the endpoint index with the run-only OpenAPI contract and RFC 9457-style reusable problems.
- Updated contract README, repository README, and implementation status.

No application, runner, frontend, persistence, infrastructure, or runtime product code was added by this iteration.

## 19. Verification commands and exact results

Verification used Temurin Java 25.0.3, Gradle 9.7.1, and Node.js 24.20.0.

| Command | Result | Exact outcome |
|---|---|---|
| `npm --prefix packages/api-contracts ci` | PASS | 7 packages installed from lockfile. |
| `npm --prefix packages/api-contracts run validate:schemas` | PASS (inside test and separately during development) | Four schemas compiled; command 2 valid/6 invalid, result 2/7, manifest 2/6, live events 5/6; semantic invariants passed. |
| `npm --prefix packages/api-contracts run lint:openapi` | PASS (inside test and separately during development) | OpenAPI valid under Redocly recommended rules with zero warnings. |
| `npm --prefix packages/api-contracts test` | PASS | Schema, fixture, semantic, and OpenAPI validation all passed. |
| `./gradlew clean check --offline` | PASS | Gradle 9.7.1 on Java 25.0.3; build successful; API 2 tests and runner 1 test passed, 0 failures/errors. |
| `npm --prefix apps/web ci` | PASS | 137 packages installed from lockfile. |
| `npm --prefix apps/web run lint` | PASS | ESLint completed with zero warnings. |
| `npm --prefix apps/web run typecheck` | PASS | Next route types generated; TypeScript completed without error. |
| `npm --prefix apps/web test` | PASS | 1 test passed; 0 failed/skipped/cancelled. |
| `npm --prefix apps/web run build` | PASS | Next.js 16.3.3 production build; `/`, `/_not-found`, `/dashboard` generated statically. |
| `npm --prefix apps/web audit --omit=dev` | PASS | `found 0 vulnerabilities`. |
| `docker compose -f infrastructure/local/docker-compose.yml config` | PASS | PostgreSQL, RabbitMQ, and MinIO configuration expanded successfully. |
| `git diff --check` | PASS | No whitespace errors. |

Two environment-only verification attempts did not execute the target check: a stale temporary `JAVA_HOME` no longer existed, and sandboxed Gradle/npm operations hit local-socket/DNS restrictions. The final wrapper build used installed Temurin 25 and the existing offline Gradle cache outside the socket-restricted sandbox; the production audit was retried with registry access. Both final authoritative commands passed. No product defect was hidden.

## 20. Deferred implementation work

- Domain types, table-driven transition tests, persistence schema/migrations, transactions, optimistic constraints, idempotency records, outbox/inbox, publisher/consumers, leases/heartbeats, reconciliation, DLQ tooling.
- Authentication/authorization, privacy-preserving tenant lookup, actual Problem Details handlers, quotas/rate limits, endpoint implementation, SSE event store/stream.
- Result engine mapping, runtime semantic validation, quality evaluation, artifact reservation/upload/verification/scan/download.
- OpenTelemetry instrumentation, collectors, metrics, logs, traces, sampling, alerts, and dashboards.
- Secret provider/redemption, network policy enforcement, object storage, Karate, launcher, container/runtime hardening, and arbitrary execution.

## 21. Risks requiring implementation validation

- Broker/service authentication, authorization, key rotation, and whether detached signing is required.
- Persistent lease/fencing correctness, clock skew, worker partitions, and external cleanup proof.
- Canonical message-byte/digest representation and conflicting-evidence quarantine operations.
- Pre-parse decoded-byte/depth/node/decompression limits and practical result/artifact quotas.
- Tenant isolation in database, broker, object references/storage, DLQ, diagnostics, and telemetry.
- Browser SSE cookie/fetch choice, Origin/CORS, revocation latency, proxy buffering, retention sizing, and atomic sequence allocation.
- Result mapping/aggregation for real Karate versions, zero tests, hooks, outlines, retries, aborts, and parallel durations.
- Artifact malware/type detection, immutable upload reservations, isolated HTML origin, log/control-code rendering, and retention.
- Redaction limits for deliberately encoded secret output; contracts cannot prevent exfiltration by code legitimately given a secret.
- Trace sampling/cost and observability exporter failure behavior.
- Hostile-execution controls in ADR-006 remain unresolved; Docker is not approved as a sufficient security boundary.

## 22. Recommended next vertical slice

Implement authenticated, organization-scoped **Project creation/retrieval plus immutable FeatureRevision creation/retrieval** with PostgreSQL migrations, tenant/uniqueness/immutability constraints, RFC 9457 handlers, audit fields, module boundaries, and cross-tenant/integration tests.

That slice proves control-plane authorization, persistence, contracts, and quality without crossing the execution boundary. It must exclude run orchestration, RabbitMQ, SSE, object storage, secrets, Karate, and container execution. Before any arbitrary execution slice, complete the dedicated hostile-execution security architecture and its executable release gate.

## Final status

**CLEAR SEMANTICS + MACHINE-VALIDATED CONTRACTS.**
**NO DATABASE, RABBITMQ IMPLEMENTATION, AUTH IMPLEMENTATION, SSE SERVER, KARATE, OR CONTAINER EXECUTION.**
Arbitrary execution remains impossible.
