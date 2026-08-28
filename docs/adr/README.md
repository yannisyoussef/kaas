# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [001](001-monorepo.md) | Monorepo | IMPLEMENTED |
| [002](002-modular-monolith.md) | Modular control plane with a separate execution boundary | IMPLEMENTED |
| [003](003-java-spring-boot.md) | Java 25, Spring Boot 4.1.1, Gradle 9.7.1 | IMPLEMENTED |
| [004](004-postgresql.md) | PostgreSQL for control-plane persistence | IMPLEMENTED for Project/FeatureRevision and versioned configuration |
| [006](006-docker-runner.md) | Per-run container isolation candidate | PROPOSED; execution disabled |
| [007](007-sse.md) | Bounded durable SSE replay for run events | PROPOSED |
| [009](009-state-machine.md) | Separate lifecycle, cancellation, outcomes, and quality evaluation | PROPOSED |
| [011](011-structured-results.md) | Structured execution evidence and artifact manifests | PROPOSED |
| [013](013-at-least-once-execution-protocol.md) | At-least-once protocol with outbox, inbox, and fencing | PROPOSED |
| [014](014-project-feature-revision-slice.md) | Authenticated Projects and immutable FeatureRevisions | IMPLEMENTED |
| [015](015-versioned-execution-configuration.md) | Immutable Environment/RunProfile configuration and metadata-only SecretReferences | IMPLEMENTED |
| [016](016-test-run-intent-and-immutable-snapshot.md) | CREATED TestRun intent with a sealed immutable execution snapshot | IMPLEMENTED |
| [017](017-transactional-scheduling-and-outbox.md) | Transactional CREATED to QUEUED scheduling with execution attempt, queue-time dispatch intent, and outbox | IMPLEMENTED |

Deferred topics without active decisions remain: RabbitMQ topology, outbox relay/publisher, consumer inbox, worker claim and lease fencing, concrete object-storage/upload adapter, secret delivery mechanism, hostile-execution runtime, and OpenTelemetry implementation. This iteration decides transport-neutral reliability, SSE replay, lifecycle/outcomes, and result/artifact semantics only.

`IMPLEMENTED` means verified by repository code or tooling. `PROPOSED` means design intent only. `DEFERRED` means no decision is active and implementation must not assume one.
