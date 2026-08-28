# Environment and RunProfile Slice Report

Status date: 2026-08-28

## 1. Executive summary

KaaS now has a second authenticated control-plane vertical slice for reproducible execution configuration. Project-scoped SecretReference metadata, Environment identities with immutable EnvironmentRevisions, and RunProfile identities with immutable RunProfileRevisions are implemented through signed-JWT HTTP APIs and PostgreSQL. A profile revision pins one exact environment revision, so later edits cannot change saved execution intent.

Secret values, provider topology, secret redemption, TestRun, messaging, object storage, Karate, and all execution paths remain absent. The API dependency guard rejects their client/runtime libraries.

## 2. Implemented API surface

The following endpoints are implemented under `/api/v1/projects/{projectId}`:

- `POST/GET /secret-references` and `GET /secret-references/{secretReferenceId}`
- `POST/GET /environments` and `GET /environments/{environmentId}`
- `POST/GET /environments/{environmentId}/revisions` and `GET /environments/{environmentId}/revisions/{revisionId}`
- `POST/GET /run-profiles` and `GET /run-profiles/{runProfileId}`
- `POST/GET /run-profiles/{runProfileId}/revisions` and `GET /run-profiles/{runProfileId}/revisions/{revisionId}`

Environment and RunProfile creation atomically create revision 1. Logical identity responses omit mutable latest-revision state. Revision history returns summaries; revision item endpoints return full immutable content. There are no update/delete routes. All responses use `Cache-Control: no-store`; every POST requires `Idempotency-Key`.

## 3. Environment model

Environment is a stable UUID identity owned by one organization and Project. It contains only `environmentId`, `projectId`, a case-sensitive project-unique name, creation audit, and an internal next-revision counter/version. It has no description to reduce the surface where users might paste secrets.

An EnvironmentRevision contains a UUID, its parent identity, contiguous revision number, normalized plain variables and secret bindings, a server digest, and creation audit.

## 4. Environment revision immutability

The application exposes only create/read operations. PostgreSQL stores a revision header as unsealed, inserts normalized entries, and permits exactly one header transition from `sealed=false` to `sealed=true`. A deferred constraint trigger rejects an unsealed aggregate at commit. After sealing, header update/delete and child insert/update/delete are rejected. This closes the late-child-insert gap that a parent-only immutable trigger would leave.

## 5. Variable model

Configuration keys are case-sensitive ASCII portable identifiers matching `^[A-Za-z_][A-Za-z0-9_.-]{0,127}$`. A key is unique across plain variables and secret bindings in an EnvironmentRevision.

Supported plain values are:

- `STRING`: exact well-formed Unicode, control-free, maximum 4096 UTF-8 bytes;
- `INTEGER`: exact JSON integer in `-9007199254740991..9007199254740991`;
- `BOOLEAN`: exact JSON boolean.

Null, decimal, nested object/array, coercion, and arbitrary JSON are rejected. Limits are 100 environment variables, 50 secret bindings, 100 profile overrides, and an effective execution snapshot of at most 500 plain values and 100 secret keys.

## 6. Secret-reference model

SecretReference is project-scoped logical metadata with exactly an opaque UUID, project UUID, bounded name, creator, and timestamp. Environment secret bindings contain only a configuration key and this UUID.

There is no value, ciphertext, provider, provider path, URI, access token, credential, version locator, capability, resolve, reveal, update-value, or redemption field/endpoint. Possession of the UUID grants no authority. Unknown and foreign references use concealed `404 NOT_FOUND`. Future capability minting must be separately authorized and reviewed.

## 7. RunProfile model

RunProfile is a stable project-scoped identity with project-unique case-sensitive name and creation audit. RunProfileRevision contains:

- one exact `environmentRevisionId`;
- up to 100 unique tags;
- parallelism `1..32`;
- scenario retry attempts `1..5` and delay `0..30000` ms;
- execution timeout `1..3600` seconds;
- existing artifact types and byte limits (`100 MiB` per artifact, `500 MiB` total, per-artifact not above total);
- up to 100 typed plain configuration overrides;
- digest and creation audit.

Scenario IDs, network-policy placeholders, quality gates, and secret overrides are deliberately absent.

## 8. Environment/profile binding

Every RunProfileRevision references one immutable EnvironmentRevision using a composite organization/project/environment/revision foreign key. It never points only to the mutable Environment identity. A later environment revision does not alter an existing profile revision; adopting it requires a new profile revision.

The proposed runner command contract now identifies both `environmentId` and `environmentRevisionId`, calls the ordinal `revisionNumber`, and restricts resolved scalars to the types this slice can produce.

## 9. Configuration precedence

Future snapshot assembly has one currently meaningful merge boundary:

```text
EnvironmentRevision plain values and secret bindings
        ↓
RunProfileRevision plain overrides
        ↓
Future explicit TestRun overrides (absent)
```

A profile may add a plain key or replace an environment plain key only with the same explicit type. Type changes and plain/secret collisions are conflicts. Secret bindings are inherited and cannot be overridden. There is no magical coercion or nested merge.

## 10. Canonical digest strategy

Both digests use SHA-256 rendered as lowercase `sha256:` plus 64 hexadecimal characters. Every field is encoded as a four-byte big-endian length followed by exact UTF-8 bytes.

Environment content begins with `kaas.environment-revision-content.v1`, then sorted typed variables and sorted secret bindings. RunProfile content begins with `kaas.run-profile-revision-content.v1`, then the pinned environment identity, revision identity and content digest, sorted tags, fixed-order settings, sorted artifact types/bounds, and sorted typed overrides.

Resource IDs/audit fields that do not define environment content are excluded. JSON property order, whitespace, request list order for semantic sets, and JVM map iteration do not affect results. Golden vectors and mutation/order tests make the format executable documentation.

## 11. Persistence schema

Flyway V2 adds `secret_references`, `environments`, `environment_revisions`, `environment_revision_entries`, `run_profiles`, `run_profile_revisions`, `run_profile_revision_overrides`, `run_profile_revision_tags`, and `run_profile_revision_artifact_types`.

Normalized sum-type rows enforce exactly one valid STRING/INTEGER/BOOLEAN/SECRET_REFERENCE payload. Composite foreign keys carry organization and Project through every parent edge, including profile-to-environment-revision and binding-to-secret-reference. Exact names, revision numbers, keys, tags and artifact types are unique in their scopes. Numeric, identifier, digest, value, and cardinality-adjacent constraints provide database defense in depth. There are no delete cascades.

## 12. Revision concurrency

Environment and RunProfile identities persist `next_revision_number`. Revision append locks the fully tenant-scoped identity row with `SELECT ... FOR UPDATE`, allocates/increments in the same transaction, inserts/seals the aggregate, and commits. Rollback restores the counter; scoped unique constraints remain defense in depth.

Separate HTTP tests release 10 virtual-thread writers simultaneously for each revision type. Every writer succeeds, numbers are exactly 2 through 11, all unique payloads remain retrievable, and revision 1 remains unchanged.

## 13. Idempotency

The existing transaction advisory-lock/idempotency table is reused. Scope is organization, principal, operation, full parent path and key. Versioned length-prefixed request fingerprints cover normalized semantic content, including pinned revision IDs and canonical typed data.

SecretReference create, Environment create, EnvironmentRevision append, RunProfile create, and RunProfileRevision append all prove concurrent same-body first use, one replay marker, stable body/Location, and same-key changed-body `409 IDEMPOTENCY_CONFLICT`. Principal and tenant/path scopes prevent replay leakage.

## 14. Tenant isolation

Organization comes only from the validated JWT. Controllers accept no organization field. Every application/repository lookup includes trusted organization, project, and all nested parent IDs. Foreign and nonexistent project/environment/profile/revision/secret-reference combinations return the same safe `404 NOT_FOUND`.

PostgreSQL composite keys prevent cross-organization and cross-project attachment even if application validation regresses. Integration tests cover foreign tenants, same-tenant mixed projects, foreign secret references, and foreign environment revisions.

## 15. Error model

The centralized RFC 9457 pipeline remains authoritative. Stable codes include resource-specific name conflicts, `VALIDATION_FAILED` for invalid or incompatible configuration, `IDEMPOTENCY_CONFLICT`, and concealed `NOT_FOUND`. Responses contain a request ID and safe JSON pointers when appropriate; submitted configuration values, secret metadata, JWTs, SQL and constraint details are not reflected.

## 16. Security review

The design used explicit identity/revision boundaries, normalized database ownership and sealing, and a deny-by-construction secret model. New configuration responses are non-cacheable. Mutation logs contain operation and trusted organization only, not configuration keys/values, digests, secret-reference IDs, JWTs, request bodies, or idempotency keys.

Architecture and build guards prohibit control-plane dependencies on the runner, Karate, Spring AMQP/RabbitMQ, MinIO, docker-java, Vault, AWS Secrets Manager, Azure Key Vault, or Google Secret Manager. No execution or secret-redemption code exists.

Three final independent read-only reviews passed after remediation. Backend/database review found no blocker in composite ownership, JDBC locking, sealing, or immutability; API/security review found and then verified the correction of missing OpenAPI name-conflict codes and reported no remaining security blocker; QE initially identified acceptance-evidence gaps, then issued a final `PASS — release-ready` after exact boundaries, all-five-POST isolation, canonical mutation cases, mixed-parent cases, and exact PostgreSQL constraint assertions were added and rerun.

## 17. Test strategy/evidence

The mandatory PostgreSQL suite uses Testcontainers with `postgres:16.10-alpine`, real random-port HTTP, RSA-signed JWTs, bounded virtual-thread concurrency, direct SQL constraint attacks, and no H2/skip-on-no-Docker path. The final Java suite passes 23 API tests plus one non-executing runner test.

Coverage includes lifecycle/history, typed round trips, exact count/UTF-8/integer/execution-setting boundaries, canonical golden vectors, semantic reordering and mutation, raw-secret mass assignment rejection, tenant/mixed-parent IDOR, both 10-writer races, all five POST idempotency conflicts/races, Flyway V2, composite ownership, exact SecretReference columns, sealing and direct immutable-row attacks, ArchUnit boundaries, and forbidden runtime dependencies.

## 18. Files changed

- `apps/api/src/main/java/com/kaas/api/controlplane/{api,application,domain,infrastructure}`: configuration APIs, use cases, policy/domain records, and JDBC port adapter.
- `apps/api/src/main/resources/db/migration/V2__environment_run_profile_slice.sql`: relational schema, constraints, indexes, sealing and compatibility triggers.
- `apps/api/src/test/java/com/kaas/api/ConfigurationHttpIntegrationTests.java` and `controlplane/domain/ConfigurationPolicyTest.java`: integration/concurrency/database and canonicalization/boundary tests.
- `apps/api/build.gradle.kts` and `ControlPlaneArchitectureTest.java`: execution/secret-provider dependency and package guards.
- `docs/api/openapi-v1.yaml`, ADR-015, architecture/security/status documentation, and this report.
- `packages/api-contracts/runner-command.schema.json`, fixtures, and semantic validator: exact EnvironmentRevision snapshot alignment.

## 19. Verification commands/results

Passed on Java 25.0.3, Gradle 9.7.1, Node.js 24.19.0, and Docker-backed PostgreSQL:

```text
GRADLE_USER_HOME=/private/tmp/kaas-review-gradle ./gradlew clean check --warning-mode all --no-daemon
npm --prefix packages/api-contracts ci
npm --prefix packages/api-contracts test
npm --prefix packages/api-contracts run lint:openapi
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web audit --omit=dev
docker compose -f infrastructure/local/docker-compose.yml config --quiet
git diff --check
```

Results: Gradle `BUILD SUCCESSFUL`; 23/23 API tests and 1/1 runner test; all four JSON Schemas and 36 fixtures behaved as expected; OpenAPI zero warnings; web lint/typecheck/render/build green; production audit found 0 vulnerabilities; Compose and whitespace validation green. `verifyNoExecutionDependencies` passed inside `check`.

The official Gradle releases and 9.7.1 release notes were rechecked on 2026-08-28: 9.7.1 remains the latest stable and recommended patch; 9.8 is available only as milestone builds, so the wrapper was intentionally not moved to a prerelease.

## 20. Residual risks

- Membership remains the only product authorization level; roles and organization provisioning are deferred.
- Rate limiting and idempotency-row retention are not implemented.
- Plain configuration values are intentionally readable to authorized project members and stored unencrypted; clients must not place secrets in these fields.
- SecretReference metadata has no useful runtime authority until a separately reviewed provider/capability subsystem exists.
- Environment/profile history grows without retention/delete support.
- TestRun snapshot assembly and its transaction boundary do not exist yet.
- Full distributed tracing/export and captured log-redaction tests remain deferred.
- Hostile execution, egress, artifact and secret-delivery controls remain unapproved and absent.

## 21. Recommended next slice

Implement TestRun intent and immutable snapshot persistence only: accept exact FeatureRevision IDs plus one RunProfileRevision ID, resolve and persist the deterministic non-secret snapshot transactionally, preserve metadata-only secret bindings without redeeming them, and expose create/get/list with idempotency and tenant tests. Keep RabbitMQ, outbox/inbox dispatch, SSE, object storage, secret capability minting, Karate, and runner/container activation outside that slice.
