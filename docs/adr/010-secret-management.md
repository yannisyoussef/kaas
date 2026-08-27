# ADR-010: Secret management

- **Status:** Accepted for bootstrap

## Context
KaaS needs a maintainable, observable platform while keeping the MVP small and preventing untrusted test execution from entering the control plane.

## Decision
Store references and resolve secrets only at the reviewed execution boundary.

## Alternatives
A larger set of microservices, a different persistence/transport/runtime choice, or deferring the concern were considered and rejected for the MVP unless noted in the security review.

## Advantages
Clear ownership, independently testable boundaries, straightforward local development, and a migration path when scale or operational requirements justify change.

## Disadvantages
The initial design carries some operational and interface complexity, and the chosen technology introduces its ecosystem's maintenance costs.

## Consequences
Implementations must preserve the control-plane/execution-plane separation, version contracts, record decisions in tests, and avoid leaking infrastructure into domain logic.
