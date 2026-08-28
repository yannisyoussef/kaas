# ADR-002: Modular monolith

## Status

IMPLEMENTED

## Context

The control plane will coordinate projects, immutable feature revisions, configuration, runs, results, and artifact metadata. These capabilities share transactions and invariants during the MVP. User-controlled test definitions are executable content and must not run in the control-plane process.

## Decision

Implement the control plane as one Spring Boot deployable organized by business capability. Keep external infrastructure behind narrow adapters where a real substitution or test boundary exists. Keep the runner as a separate process/deployable with no code path that executes tests in the API.

## Alternatives considered

- A microservice per control-plane capability.
- One process containing both the API and Karate execution.
- A flat controller/service/repository package layout.

## Why alternatives were rejected

Microservices add distributed transactions and operations without current scale or team boundaries. In-process execution violates the primary trust boundary. A flat technical-layer layout makes capability ownership and dependency rules harder to preserve.

## Advantages

- Simple deployment and transactional consistency for the MVP.
- Capability-focused code remains testable and extractable if evidence later supports it.
- The execution trust boundary is visible at process and module level.

## Disadvantages

- Module boundaries require architecture tests and review discipline.
- A single control-plane deployable scales and releases as one unit.

## Consequences

The Project/FeatureRevision slice proves inward dependency direction using domain, application, API, and infrastructure packages. ArchUnit prevents domain-to-framework, API-to-persistence, and control-plane-to-runner/Karate dependencies. This ADR does not approve the execution protocol or Docker security design.

## Validation and revisit conditions

Validated by the Project/FeatureRevision slice and architecture tests. Revisit only after measured scaling, availability, ownership, or release-cadence constraints justify extraction.
