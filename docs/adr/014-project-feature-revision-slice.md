# ADR-014: Authenticated projects and immutable feature revisions

## Status

IMPLEMENTED

## Context

KaaS needs its first real control-plane capability without weakening the non-execution boundary. Project and feature source operations must be organization-scoped, retry-safe, byte-exact, and enforce important invariants in PostgreSQL rather than only in Java.

## Decision

- Trust an external OIDC-compatible issuer through Spring Security JWT resource-server validation. Require signed RS256 tokens with issuer, audience, expiry, nonblank `sub`, and one UUID `org_id` claim.
- Treat a valid trusted `org_id` as simple membership for this slice. Materialize a minimal internal organization ownership anchor on the first authorized mutation; organization lifecycle remains owned by the identity system and has no management API.
- Scope every repository lookup by trusted organization plus the full parent hierarchy. Missing and foreign resources share one concealed `404 NOT_FOUND` response.
- Create a Feature and revision 1 atomically. Keep `logicalPath` on the stable Feature identity. Revisions are insert-only and PostgreSQL rejects update or delete.
- Store source as opaque PostgreSQL text, without Karate parsing or execution. Reject empty source, NUL, forbidden C0 controls, malformed Unicode, and source above 524288 UTF-8 bytes. Hash the exact decoded string encoded as UTF-8 with SHA-256; do not normalize line endings or Unicode.
- Allocate revision numbers from a persisted per-feature counter while holding a database row lock. A composite unique constraint is the final concurrency defense.
- Require idempotency keys for all creation endpoints. Scope them by trusted organization, principal, operation, parent path, and key; serialize concurrent first use with a PostgreSQL transaction advisory lock. A changed request fingerprint returns 409.
- Use RFC 9457 Problem Details for MVC and security-filter errors, with stable codes and no submitted source, token, tenant, SQL, or constraint details.

## Alternatives considered

- Trusting an organization ID supplied in a body, path, or query.
- Looking up an object globally and checking ownership afterward.
- Mutable feature source rows.
- `max(revision_number) + 1`, a JVM lock, or optimistic retry as the primary allocator.
- Parsing or validating Karate syntax in the control plane.
- H2-backed persistence tests.

## Why alternatives were rejected

They create tenant-confusion or IDOR risks, weaken history and concurrency guarantees, couple the control plane to execution semantics, or fail to exercise PostgreSQL behavior.

## Consequences

All mutation clients must send `Idempotency-Key`. Display names and logical paths are exact and case-sensitive. Feature history consumes PostgreSQL storage by design and has no delete operation. An external issuer and PostgreSQL are required for product endpoints; non-routable OIDC defaults fail closed. RabbitMQ, MinIO, runs, SSE, secrets, and execution remain disconnected.

## Validation and revisit conditions

Validate with signed-JWT HTTP tests, PostgreSQL Testcontainers, concurrent writers, direct trigger/constraint tests, OpenAPI linting, and ArchUnit. Revisit membership roles, organization provisioning, retention, source storage, or path semantics only with a concrete product or operational requirement.
