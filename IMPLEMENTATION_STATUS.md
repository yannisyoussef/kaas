# Implementation Status

Status date: 2026-08-28

This document describes repository reality after the dispatch consumption, claim, and lease slice. Product vision and execution architecture do not imply runtime capability.

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
- Generalized, explicitly typed PostgreSQL outbox owning its own immutable payload, with a controlled message-type enum, an optional dispatch reference, and a V5 delivery-scheduling model the database rather than the relay process owns.
- Outbox relay publishing to RabbitMQ with correlated publisher confirms, persistent messages, mandatory routing, bounded deterministic backoff, terminal dispositions retained as evidence, and fail-closed digest verification before publication.
- Relay claim protocol using FOR UPDATE SKIP LOCKED with lease expiry, so multiple relays take disjoint work, a crashed relay strands nothing, and a revived relay cannot overwrite a newer disposition. No database transaction is held across broker I/O.
- At-least-once publication with an explicit, tested crash-after-confirm duplicate window; stable message identity and semantic digest make duplicates safe for a future consumer.
- Production run scheduler moving CREATED to QUEUED in bounded, deterministically ordered batches through the established use case, safe across replicas.
- Idempotent run-create replay now returns the run's current canonical representation and ETag rather than a reconstructed CREATED view, so one resource never advertises two strong validators.
- Per-organization admission control: ceilings on active (CREATED or QUEUED) and queued runs, enforced under an organization-scoped PostgreSQL advisory lock so concurrent creates cannot overshoot, answered by a partial index rather than a full scan.
- Admission returns 429 RUN_QUOTA_EXCEEDED with no counts, capacities, other tenants, or fabricated Retry-After. An already-successful idempotent replay is resolved before admission and still succeeds at capacity; a new key does not.
- Queued-run ceiling enforced by the scheduler rather than at creation, so a burst is held at CREATED instead of becoming attempts, dispatches, outbox rows, and broker messages.
- Durable scheduler backoff and quarantine in PostgreSQL, replacing a per-process in-memory cooldown: the delay survives a restart, is shared across replicas, and never mutates run lifecycle, version, or outcome.
- Early run cancellation: `POST /api/v1/runs/{runId}/cancellations` ends a CREATED or QUEUED run in one transaction with no STOPPING phase, because no worker owns the run. Idempotent by state rather than by key, tenant-scoped with concealed 404, and a 409 rather than a false cancellation when the run already ended for another reason.
- Queue-deadline reaping: the deadline the scheduler has written since the first slice is now enforced. An expired run completes TIMED_OUT with reason QUEUE_DEADLINE and phase QUEUE, never as a cancellation, in bounded batches with durable backoff and quarantine shared with the scheduler's control table.
- Dispatch suppression: a terminating run withdraws any outbox message no relay is currently holding — unclaimed, in retry backoff, or abandoned under an expired lease — in the same transaction, spending no publish attempt and inventing no failure code while keeping whatever delivery history the message really had. A message under a live relay lease is deliberately left to publish rather than falsely recalled, and suppressed messages are excluded from the relay dead-letter count.
- Admission capacity is genuinely released. Before this slice the ceiling counted runs that could never leave, so an organization at its limit was permanently stuck.
- PostgreSQL V7 rewrite of the scheduling-only guard set as a unit — `guard_supported_test_run_update`, `require_complete_scheduling_bundle`, `guard_run_lifecycle_event`, `ck_run_lifecycle_events_schedule` (replaced by `ck_run_lifecycle_events_transition`), `guard_outbox_message`, `guard_run_scheduling_control` — permitting exactly four transitions and failing closed on everything else, plus terminal columns, ten validated check constraints across the three tables, a nullable lifecycle-event attempt reference, and partial indexes for the reaper's selection and the relay's dead-letter count.
- Migration-upgrade testing as a permanent gate: every migration is verified against an empty database and against a populated previous-version database with that version's triggers installed, and the populated fixture is proven to reach what the upgrade changes before the upgrade runs.
- The dispatch consumer ships **disabled by default**. It is complete and proven end to end against a real broker, but nothing in the repository can yet send a heartbeat, so enabling it without a worker claims every run and then loses its lease sixty seconds later — recording FAILED / LEASE_LOST / CLAIM, which asserts the platform reached the run and lost the worker holding it when no worker ever existed. Until a heartbeating worker exists, the queue deadline's TIMED_OUT is the honest ending. Starting the consumer with lease reconciliation disabled is refused outright, because claimed runs would then hold admission capacity with nothing able to release them.
- Production RabbitMQ dispatch consumer with a durable inbox: strict contract validation of the exact published bytes (unknown properties rejected, size checked before parsing, strict UTF-8, semantic digest re-derived), identity corroborated against the persisted dispatch row before any state is read, and a decision committed to PostgreSQL before the broker is acknowledged.
- Consumer inbox keyed by application message identity rather than delivery tag, with redelivery counted on the existing decision instead of recorded as a second one, and a known identity carrying different bytes recorded as an integrity conflict rather than resolved.
- Requeue reserved for the control plane's own failures and paced so an outage cannot become a hot loop; malformed, unsupported, and conflicting messages refused without requeue to a consumer dead-letter exchange; stale messages acknowledged rather than dead-lettered.
- Authoritative QUEUED to CLAIMED claim: compare-and-set on the run, the assignment on its attempt, and the lifecycle event in one transaction, with assignment epoch 1 as the fencing token and a server-controlled worker identity that is audit rather than authorization.
- Server-controlled worker lease with an internal heartbeat surface on its own security chain: 30-second lease, 10-second heartbeat, 30-second recovery window. A heartbeat renews only the assignment it can name, bumps no version, emits no event, and cannot revive an expired or fenced lease.
- Lease-expiry recovery and cancellation of owned work, both through CLAIMED to STOPPING to COMPLETED with the assignment fenced in the same transaction as the lifecycle move, settling as FAILED/LEASE_LOST/CLAIM or CANCELLED/USER_REQUESTED/CANCELLATION. Claimed work always releases admission capacity.
- PostgreSQL V8 rewrite of the lifecycle guard set as a unit for ownership: the attempt guard moves from insert-only to claim/heartbeat/fence, the bundle invariant gains CLAIMED and STOPPING branches, a terminal run may retain no live assignment, and every transition's actor is pinned to the identity entitled to it.
- Final Java 25/Gradle 9.7.1 clean verification: 154 API tests plus 1 runner test passed with zero failures/skips using PostgreSQL and RabbitMQ Testcontainers; contract, web, audit, Compose, and whitespace gates also passed.

## Scaffolded, not product functionality

- The runner prints a status message and cannot execute Karate or any caller-supplied command. It does now start containers: a trusted launcher runs one repository-controlled security probe, selected from a fixed server-side enumeration, under an immutable hardened profile. That component holds Docker daemon access and is the most privileged code in the repository.
- The web application contains a landing page and placeholder dashboard with no API client or product data.
- Run create/get/list/snapshot/cancellations OpenAPI paths are implemented. Events/results/artifacts and execution JSON Schemas remain proposed contracts. The worker heartbeat is an internal service operation, deliberately outside the public contract.
- Docker Compose PostgreSQL is configured for the API. RabbitMQ and MinIO are not connected to feature lifecycle.
- Docker Compose binds PostgreSQL, RabbitMQ, and MinIO to loopback and defines development health checks. Configuration validation passes; container startup was not verified because the local Docker daemon was unavailable.

## Designed or proposed

- Future control-plane capabilities beyond versioned execution configuration and a separately deployable execution plane.
- PostgreSQL schemas for orchestration and result metadata beyond the implemented scheduling bundle and outbox delivery state.
- Per-run container isolation as a candidate only; it is not approved as a sufficient hostile-code boundary.
- Product concepts beyond implemented QUEUED scheduling and dispatch publication, including execution, results, artifact storage, and quality gates.
- Orthogonal run lifecycle, cancellation, test outcome, infrastructure outcome, and quality-gate semantics beyond the early terminal transitions above: every phase a worker owns, its STOPPING protocol, and quality-gate evaluation.
- Assignment fencing, leases, phase deadlines beyond the queue deadline, consumer inbox semantics, consumer-side DLQ classification, structured results/artifacts, and bounded SSE replay.

## Planned and intentionally absent

- RabbitMQ consumers, consumer inbox deduplication, or consumer dead-letter handling. Publication is implemented; the queue may hold published dispatch intents that no production code consumes.
- Redis.
- SSE implementation.
- Object-storage integration.
- Secret values, provider mapping, storage, resolution, redemption, capability minting, or injection.
- Karate dependencies, a production secret provider, secret capability issuance, network enforcement beyond deny-all, command delivery, or arbitrary test execution. A runner image and a container launcher exist and are deliberately limited to the synthetic probe. Source capability issuance, an egress policy model, and ExecutionCommand production now exist under ADR-023; nothing executes a command.
- OpenTelemetry instrumentation.
- Lifecycle mutation handlers beyond CREATED to QUEUED, consumer inbox, lease reconciliation, quality evaluation, outbox retention policy, and durable run event storage beyond the scheduling transition record.

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

No item above is runtime behavior. KAA-004 is evidenced rather than closed: a hostile-execution security architecture (ADR-022) and an executable release gate exist and pass against a trusted synthetic probe on standard hardened Docker. It is not approved for user content — the sandbox shares the host kernel.

ADR-023 adds the second gate. Source capability issuance and an egress policy model now exist; a secret capability model exists but is never issued, because no production secret provider does. Execution requires both an approved sandbox boundary and a valid assignment-scoped authorization, and neither substitutes for the other.

## Current vertical slice

Authenticated, organization-scoped Project/FeatureRevision and Environment/RunProfile lifecycles, TestRun/RunSnapshot persistence, the single CREATED to QUEUED scheduling transition, at-least-once publication of the resulting dispatch intent to RabbitMQ, and per-organization admission control with durable scheduler backoff are implemented. A background scheduler triggers the transition and a separate relay loop publishes the outbox; PostgreSQL remains authoritative for run state, attempt identity, dispatch intent, and delivery state. Durable consumption, worker claim, assignment-epoch fencing, and a server-controlled lease with heartbeats and expiry recovery are implemented; the consumer is shipped disabled by default because nothing yet heartbeats, and enabling it without a worker would terminalize every run as a lost lease, which is a diagnosis no worker earned. Queue-time dispatch intent is still deliberately not a claim-time execution command. Claiming records ownership of an infrastructure attempt and grants no permission to execute; a separate ExecutionAuthorization grants that, scoped to one attempt and assignment epoch and one worker, bounded by the lease, and revalidated against authoritative state on every capability redemption. A short-lived source capability and an immutable ExecutionCommand are issued under it. **Nothing executes the command**: it is not published to a broker, not reachable from the dispatch consumer, and not handed to the sandbox launcher, and the run stays CLAIMED. Secret-bearing runs stop at authorization because no production secret provider exists. Execution itself remains absent until its delivery protocol and the ADR-023 prerequisites are implemented and validated.

## Security gate

Arbitrary execution remains disabled, and `kaas.execution.enabled=true` refuses to start the application. A container launcher exists under ADR-022 with enforceable controls and adversarial tests, exercised against a trusted synthetic probe. Execution authorization and a source capability exist under ADR-023 and are covered by its own adversarial review.

ADR-023 was implemented without ADR-022's first prerequisite (a stronger kernel boundary than shared-kernel Docker) being met, and that is deliberate rather than overlooked: authorization is a control-plane decision that nothing executes, so building it does not admit user content to any sandbox. That prerequisite still gates execution itself. Do not add Karate, a real secret provider, command delivery, or the CLAIMED to PROVISIONING transition until the prerequisites named in both ADR-022 and ADR-023 are met and the gate passes on the deployment's own runtime.

See `CONTRACT_LIFECYCLE_ARCHITECTURE_REPORT.md` for the decisions, adversarial review, changed files, verification evidence, and deferred implementation work.

See `PROJECT_FEATURE_SLICE_REPORT.md` for the implemented API/schema decisions, verification evidence, residual risks, and independent specialist reviews.

See `ENVIRONMENT_RUN_PROFILE_SLICE_REPORT.md` for the versioned-configuration model, canonicalization, database sealing, concurrency/idempotency evidence, and independent specialist reviews.

See `TEST_RUN_INTENT_SLICE_REPORT.md` for the CREATED-only lifecycle boundary, snapshot canonicalization/persistence, API contract, security evidence, runner-command mapping, and independent reviews.

See `SCHEDULING_OUTBOX_SLICE_REPORT.md` for the recovered scheduling slice: the queue-time/claim-time protocol correction, transactional bundle, compare-and-set concurrency evidence, persistence guards, contract changes, security review, and independent reviews.

See `ADMISSION_SCHEDULER_HARDENING_REPORT.md` for the admission and scheduler hardening slice: the amplification rationale, admission and concurrency model, idempotency interaction, queue admission, durable backoff and quarantine, migration-upgrade strategy, security review, and independent reviews.

See `RABBITMQ_OUTBOX_RELAY_SLICE_REPORT.md` for the relay slice: the generalized outbox decision, delivery-scheduling model, relay claim protocol, publisher confirms, retry and terminal policy, at-least-once crash-window analysis, scheduler trigger, health and observability, security review, and independent reviews.
