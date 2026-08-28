# ADR-004: PostgreSQL for control-plane persistence

## Status

IMPLEMENTED

## Context

The next vertical slice needs tenant-scoped projects, immutable feature revisions, constraints, audit timestamps, and transactional consistency. Later run orchestration will need atomic state changes and outbox records. No persistence adapter or schema exists yet.

## Decision

Use PostgreSQL as the system of record for implemented Project, Feature, FeatureRevision, tenant-anchor, and idempotency metadata. Store large report/log/artifact bytes outside PostgreSQL. Future run persistence remains outside this implemented slice.

## Alternatives considered

- An embedded or local file store for the first slice.
- A document database.
- PostgreSQL JSON columns for the entire domain model.

## Why alternatives were rejected

Local files do not prove tenant constraints, transactions, or production-like migrations. The current domain is relational and benefits from foreign keys and uniqueness constraints. Storing every aggregate as opaque JSON would weaken database-enforced invariants and queryability.

## Advantages

- Strong transactions and mature relational constraints.
- Good support in Spring and Testcontainers.
- Supports relational data with selective JSON use when justified.

## Disadvantages

- Requires migrations, operational maintenance, and integration tests.
- Horizontal write scaling is more complex than with some distributed stores.

## Consequences

The first migration must define tenant scope, identity, immutability, constraints, and actual query indexes. Persistence entities must not become public API DTOs. Broker publication must eventually use an outbox rather than a dual write.

## Validation and revisit conditions

Validated structurally by Flyway migrations, JPA schema validation, composite tenant foreign keys, direct database invariant tests, and PostgreSQL Testcontainers. Runtime container evidence depends on Docker availability. Revisit only if measured data shape, scale, availability, or operational constraints make PostgreSQL unsuitable.
