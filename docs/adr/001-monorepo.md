# ADR-001: Monorepo

## Status

IMPLEMENTED

## Context

The API, web scaffold, runner scaffold, contracts, local infrastructure, and architecture documents change together during the foundation phase. The project has one delivery team and no independently operated product services yet.

## Decision

Keep all KaaS source, contracts, infrastructure definitions, and engineering documentation in one Git repository. Use top-level directories to make ownership and deployable boundaries visible.

## Alternatives considered

- Separate repositories for web, API, runner, and contracts.
- A single undifferentiated application directory.

## Why alternatives were rejected

Separate repositories add version coordination and CI/release overhead before teams or release cadences diverge. A single application directory would obscure the control-plane/execution-plane trust boundary.

## Advantages

- Contracts and their consumers can change in one reviewed commit.
- One CI workflow can validate the complete foundation.
- Repository-level documentation can describe implementation status consistently.

## Disadvantages

- CI must avoid rebuilding unrelated areas unnecessarily as the repository grows.
- Shared repository access does not provide service-level access isolation.

## Consequences

Directory boundaries are not security boundaries. The API and runner remain separate build modules and future deployables. Cross-module dependencies must be explicit and tested.

## Validation and revisit conditions

Validated by independent Gradle modules, independent npm packages, and CI jobs. Revisit only when ownership, release cadence, regulatory isolation, or repository scale creates measured friction.
