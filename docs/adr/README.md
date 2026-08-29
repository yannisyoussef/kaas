# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [001](001-monorepo.md) | Monorepo | IMPLEMENTED |
| [002](002-modular-monolith.md) | Modular control plane with a separate execution boundary | IMPLEMENTED |
| [003](003-java-spring-boot.md) | Java 25, Spring Boot 4.1.1, Gradle 9.7.1 | IMPLEMENTED |
| [004](004-postgresql.md) | PostgreSQL for control-plane persistence | IMPLEMENTED for Project/FeatureRevision and versioned configuration |
| [006](006-docker-runner.md) | Per-run container isolation candidate | SUPERSEDED by [ADR-022](022-hostile-execution-boundary-and-synthetic-probe.md) |
| [007](007-sse.md) | Bounded durable SSE replay for run events | PROPOSED |
| [009](009-state-machine.md) | Separate lifecycle, cancellation, outcomes, and quality evaluation | PROPOSED |
| [011](011-structured-results.md) | Structured execution evidence and artifact manifests | PROPOSED |
| [013](013-at-least-once-execution-protocol.md) | At-least-once protocol with outbox, inbox, and fencing | PARTIALLY IMPLEMENTED: outbox and publication implemented; inbox and fencing proposed |
| [014](014-project-feature-revision-slice.md) | Authenticated Projects and immutable FeatureRevisions | IMPLEMENTED |
| [015](015-versioned-execution-configuration.md) | Immutable Environment/RunProfile configuration and metadata-only SecretReferences | IMPLEMENTED |
| [016](016-test-run-intent-and-immutable-snapshot.md) | CREATED TestRun intent with a sealed immutable execution snapshot | IMPLEMENTED |
| [017](017-transactional-scheduling-and-outbox.md) | Transactional CREATED to QUEUED scheduling with execution attempt, queue-time dispatch intent, and outbox | IMPLEMENTED |
| [018](018-outbox-relay-and-rabbitmq-publication.md) | Outbox relay with at-least-once RabbitMQ publication, database-owned retry, and a production scheduling trigger | IMPLEMENTED |
| [019](019-tenant-admission-and-durable-scheduler-backoff.md) | Per-organization run admission, queued-run ceiling, durable scheduler backoff, and migration-upgrade testing | IMPLEMENTED |
| [020](020-early-terminal-lifecycle-and-queue-deadline-reaping.md) | Early run cancellation, queue-deadline reaping, dispatch suppression, and the scheduling-only guard rewrite | IMPLEMENTED |
| [021](021-durable-dispatch-consumption-fencing-and-worker-lease.md) | Durable dispatch consumption, consumer inbox, worker claim, assignment-epoch fencing, and lease recovery | IMPLEMENTED |
| [022](022-hostile-execution-boundary-and-synthetic-probe.md) | Hostile-execution trust boundary, hardened sandbox, trusted synthetic probe, and executable release gate | IMPLEMENTED |

Deferred topics without active decisions remain: concrete object-storage/upload adapter, source capability issuance, secret delivery mechanism, egress allowlist policy, outbox and CREATED-run retention policy, self-service quarantine recovery, and OpenTelemetry implementation. Consumer inbox and worker claim/lease fencing are decided by ADR-021, and the hostile-execution runtime by ADR-022; ADR-022 names a stronger runtime (gVisor or a microVM) as a prerequisite for admitting user content, with this gate re-run against it as the acceptance criterion.

`IMPLEMENTED` means verified by repository code or tooling. `PROPOSED` means design intent only. `DEFERRED` means no decision is active and implementation must not assume one.
