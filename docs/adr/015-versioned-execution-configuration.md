# ADR-015: Versioned execution configuration and metadata-only secret references

## Status

IMPLEMENTED

## Context

Future TestRuns need reproducible configuration. A run cannot depend on whichever Environment or RunProfile happens to be current when execution starts. Configuration also needs tenant-safe references to secrets without pretending that this control plane stores, encrypts, resolves, or authorizes secret values.

## Decision

- Model Environment and RunProfile as project-scoped logical identities with insert-only immutable revisions. Creation atomically inserts the identity and revision 1.
- Bind every RunProfileRevision to one immutable EnvironmentRevision, never to the mutable Environment identity.
- Store Environment entries as normalized, case-sensitive keys. Plain values are explicitly typed as `STRING`, `INTEGER`, or `BOOLEAN`; secret bindings contain only a project-scoped SecretReference UUID.
- Model SecretReference as project-scoped logical metadata with name and creation audit only. It has no value, ciphertext, provider, path, URI, credential, token, redemption, or reveal field. Possession of its UUID grants no authority.
- Allow RunProfileRevision to contain tag selection, bounded parallelism/retry/timeout, the existing artifact-policy shape, and plain typed configuration overrides. It does not contain network or quality-gate policy placeholders.
- Apply deterministic precedence: EnvironmentRevision entries first, then RunProfileRevision plain overrides. A profile plain override may replace an environment plain value only with the same explicit type. It cannot replace an environment secret binding. Secret bindings are inherited and cannot be overridden in this slice.
- Hash a versioned, domain-separated, length-prefixed, typed canonical representation. Keys, tags, and artifact types are sorted by natural case-sensitive Unicode order. JSON object/property/array order and whitespace do not affect digests.
- Reuse transactional POST idempotency and persisted next-revision counters with tenant-scoped row locks.
- Protect normalized revision aggregates with a database sealing protocol. A revision is inserted unsealed, children are inserted, and only the `false` to `true` seal transition is allowed. Deferred constraint triggers require a sealed revision at commit. After sealing, parent and child insert/update/delete operations are rejected.

## Consequences

### Positive

- Future runs can bind exact immutable inputs without silently following later configuration changes.
- PostgreSQL enforces tenant/project ownership, key uniqueness, bounds, profile-to-environment-revision integrity, and aggregate immutability.
- Secret values and provider topology cannot enter this API or schema through a supported field.
- Canonical digests are reproducible across JSON serializers and request ordering.

### Negative

- Normalized immutable aggregates require more tables and a deliberate sealing protocol.
- Environment and profile history grows without update/delete or retention support.
- Profiles cannot override one secret binding with another until a separately authorized secret subsystem exists.

### Neutral

- Names and keys are exact and case-sensitive; `FOO` and `foo` are distinct.
- Environment/profile descriptions, nested JSON, decimal values, scenario-ID selection, network policies, and quality gates remain absent.

## Alternatives considered

- Mutable Environment and RunProfile rows: rejected because queued work would not be reproducible.
- Binding profiles to Environment identity: rejected because saved profiles would silently change.
- Storing revisions as opaque JSONB: rejected because important key, type, reference, and ownership invariants belong in relational constraints.
- Storing encrypted or provider-specific secrets: rejected because no reviewed secret storage/redemption capability exists.
- Parent-only immutability triggers: rejected because normalized child rows could still be inserted later.
- Hashing Jackson output: rejected because property ordering and serialization details are not a semantic contract.

## Validation and revisit conditions

This decision was promoted after signed-JWT HTTP tests, PostgreSQL Testcontainers migrations and direct constraint tests, two ten-writer revision races, idempotency races for every POST operation, canonical digest vectors, tenant/mixed-parent negative tests, OpenAPI linting, and forbidden-dependency checks passed. Revisit configuration limits, decimal support, secret-binding overrides, or retention only with a concrete execution-contract or operational requirement.
