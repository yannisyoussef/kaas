# TestRun Intent and Immutable RunSnapshot Slice Report

## 1. Executive outcome

KaaS now persists authenticated TestRun intent as a version-1 `CREATED` aggregate and a distinct sealed immutable RunSnapshot. Creation returns `202 Accepted` and does not schedule, publish, execute, or cross the secret boundary.

## 2. Scope delivered

- `POST /api/v1/projects/{projectId}/runs`
- `GET /api/v1/runs/{runId}`
- `GET /api/v1/projects/{projectId}/runs`
- `GET /api/v1/runs/{runId}/snapshot`
- Flyway V3, domain policy, JDBC persistence, OpenAPI, ADR-016, architecture documentation, and automated evidence

## 3. Explicit non-goals

No scheduler, attempt, outbox/inbox, RabbitMQ adapter, runner activation, Karate dependency, process/container launch, source bundle, MinIO, SSE, result/artifact processing, secret redemption/capability, network enforcement, cancellation handler, or lifecycle mutation endpoint was added.

## 4. Public TestRun semantics

New runs have `runVersion=1`, `lifecycleState=CREATED`, `cancellationStatus=NOT_REQUESTED`, null test/infrastructure outcomes, and `qualityGateStatus=NOT_EVALUATED`. Creation/audit timestamps are server-owned. The strong representation ETag is exactly `"run-1"`.

## 5. Queue-deadline correction

`queueDeadlineAt` is neither accepted nor returned at CREATED and both queue columns are null in PostgreSQL. Queue wait starts only when a future scheduler atomically performs CREATED to QUEUED. The state-machine transition table and OpenAPI now agree.

## 6. Request contract and validation

CreateRunRequest is closed and contains only `featureRevisionIds` and `runProfileRevisionId`. Feature selection is bounded to 1–1000, duplicate revision IDs are rejected, and multiple revisions of the same logical Feature are rejected. Malformed JSON/types are 400; semantic validation is 422.

## 7. Tenant and ownership model

All lookups use the trusted JWT organization. Project create/list paths use organization plus project. Global run and snapshot reads use organization plus run ID and derive the project from stored data. Missing, foreign, and mixed-project inputs use the same concealed 404.

## 8. TestRun versus RunSnapshot

TestRun owns lifecycle identity, semantic concurrency version, orthogonal status dimensions, and audit. RunSnapshot owns immutable execution meaning. Keeping them separate permits future versioned lifecycle changes without rewriting or re-resolving accepted inputs.

## 9. Feature provenance

Each snapshot feature records Feature ID, FeatureRevision ID/number, copied logical path, and source digest. Source bodies are not duplicated. Canonical order is logical path, Feature UUID, then revision UUID.

## 10. Configuration provenance

The exact RunProfileRevision and its exact pinned EnvironmentRevision are copied as identity, revision number, and content digest. No mutable identity name or “latest revision” is consulted by snapshot reads.

## 11. Effective configuration

Environment typed scalar values are materialized first; profile same-type scalar overrides replace matching keys. The persisted result is one key-sorted typed collection. Upstream configuration validation prevents cross-type and secret override ambiguity.

## 12. Secret boundary

Secret bindings are stored and returned only as `{key, secretReferenceId}` metadata. V3 has no secret value, provider, path, URI, token, credential, ciphertext, or capability column. Snapshot reads do not join SecretReference names or invoke a provider.

## 13. Server-owned engine

The snapshot records `{engine: KARATE, version: <configured exact version>}` from `KAAS_KARATE_ENGINE_VERSION` (default `2.0.0`). The request cannot set engine type/version and unknown mass-assignment fields are rejected. This metadata does not mean Karate is installed or executable.

## 14. Canonical digest

`kaas.run-snapshot-content.v1` uses explicit collection cardinalities and four-byte big-endian length-prefixed UTF-8 fields. It covers project, exact feature/profile/environment provenance, typed effective configuration, secret-reference UUID metadata, tags, parallelism, retry, timeout, artifact policy, and engine. It excludes run ID, actor, timestamps, lifecycle, request order, and JSON formatting.

## 15. Idempotency

The request fingerprint is deliberately distinct from the snapshot digest and contains only normalized client intent. The existing transaction-scoped PostgreSQL advisory lock serializes a scope `(organization, principal, operation, project path, key)`. Replay is checked before current engine/resource resolution, returns the original CREATED representation, Location, ETag, and 202, while changed intent returns 409.

## 16. PostgreSQL V3 model

V3 adds composite FeatureRevision and pinned profile/environment ownership keys, `test_runs`, `run_snapshots`, and normalized feature/configuration/tag/artifact children. Composite FKs carry organization/project/run identity. Insert guards also verify copied feature path/digest/revision and profile/environment revision, digest, and execution settings against the exact sealed source rows. Project history is indexed by `(organization, project, created_at DESC, run_id DESC)`.

## 17. Database immutability

TestRun insertion is guarded to the exact initial state and all TestRun update/delete is fail-closed until lifecycle mutation is implemented. Snapshot construction follows unsealed header → children → seal. Only the exact false-to-true seal update is allowed; late child inserts and every subsequent header/child update/delete are rejected. A deferred trigger requires exactly one sealed digest-matching snapshot before TestRun commit.

## 18. Read and list behavior

Run GET returns the canonical persisted representation with `"run-1"`. Snapshot GET reads only materialized snapshot tables and has a digest-derived ETag. Project list uses stable `createdAt DESC, runId DESC` order and the shared bounded pagination contract. Tenant-sensitive responses are `no-store`.

## 19. Runner-command mapping

The snapshot can later supply feature provenance, effective plain configuration, logical secret references, exact environment/profile provenance, selection, parallelism, retry, timeout, artifact settings, and engine metadata. It intentionally cannot supply scheduling-owned command/message/attempt IDs, assignment epoch, deadlines, source-bundle reference/capability/digest, runtime secret capabilities, or resolved network policy. A future immutable execution-attempt plan must supply those before scheduling is possible.

## 20. Automated evidence

Coverage includes a canonical digest vector, semantic reordering, engine mutation, run-ID exclusion, duplicate Feature identity, lifecycle oracle, V1→V2→V3 PostgreSQL migration, create/get/list/snapshot, no-store/ETags, exact CREATED invariants, unauthenticated and tenant-concealed reads, principal/tenant idempotency scoping, mass-assignment rejection, semantic replay/conflict, concurrent same-key creation, exact Location/ETag replay, reproducibility after appended feature/profile/environment revisions, 1/1000/1001 selection boundaries, database sealing and mutation rejection, and absence of execution/outbox tables. The full Java, contract, web, Compose, and whitespace gates are recorded in the final verification section below.

## 21. Independent specialist reviews

- **Backend/domain, database, and distributed lifecycle:** identified the queue-deadline contradiction, composite FeatureRevision FK gap, need for sealed normalized persistence, TestRun/snapshot separation, semantic version meaning, and the future execution-attempt-plan boundary. These corrections are incorporated.
- **API/security:** required trusted-org global reads, concealed mixed ownership, distinct request fingerprint/snapshot digest, metadata-only secret responses, server-controlled engine, closed schemas, no-store responses, and explicit implemented/proposed OpenAPI status. These controls are incorporated.
- **QE/test design:** supplied release-blocking auth/tenant, lifecycle, snapshot, reproducibility, canonicalization, idempotency-race, database-constraint, boundary, and false-positive matrices. High-value cases are automated in the mandatory PostgreSQL suite; remaining future-lifecycle cases stay deferred with their unimplemented capabilities.

One reviewer initially recommended `201 Created`; the explicit product requirement for `202 Accepted` controls. The final contract makes clear that 202 means durable intent only, not asynchronous work already dispatched.

## 22. Residual risks and next safe slice

CREATED runs intentionally remain inert. Before any scheduler is enabled, KaaS must decide and persist an immutable network-policy revision, source-bundle production/authorization, runtime secret capability issuance, execution-attempt identity/fencing/deadlines, and an atomic outbox transition. Hostile-execution isolation still needs its dedicated approved security architecture and executable release gates. Cancellation/events/results/artifacts remain proposed and must not be inferred from this slice.

### Final verification record

- `GRADLE_USER_HOME=/private/tmp/kaas-gradle-home ./gradlew clean check` — passed on Java 25.0.3 with Gradle 9.7.1; 30 API tests and 1 runner test, zero failures/skips. Both API integration suites used PostgreSQL 16.10 Testcontainers and migrated empty schemas through V3.
- `npm --prefix packages/api-contracts test` — passed all strict schema fixtures/semantic checks and zero-warning Redocly OpenAPI lint.
- `npm --prefix apps/web run lint`, `typecheck`, `test`, and `build` — passed.
- `npm --prefix apps/web audit --omit=dev` — passed with 0 vulnerabilities.
- `docker compose -f infrastructure/local/docker-compose.yml config` — passed.
- `git diff --check` — passed.
