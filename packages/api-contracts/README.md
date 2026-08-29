# KaaS Contracts 1.0

**Status: MIXED IMPLEMENTED/PROPOSED CONTRACTS.** `EXECUTION_DISPATCH` is persisted to the transactional outbox and published to RabbitMQ by the relay, and consumed by the dispatch consumer (ADR-018, ADR-021). `execution-command` and `sandbox-security-attestation` are produced and consumed by the control plane (ADR-023). `runner-command` remains PROPOSED: it is a broker envelope for a delivery protocol that does not exist, and it is a separate contract from `execution-command` rather than a wrapper around it — the two differ in shape today, and reconciling them belongs to the slice that actually delivers a command. No component executes any contract.

## Contract set

- `execution-dispatch.schema.json` — implemented immutable queue-time identity for one unassigned attempt; never an executable runner command.
- `runner-command.schema.json` — immutable `EXECUTION_COMMAND` for one fenced infrastructure attempt.
- `runner-result.schema.json` — structured `EXECUTION_RESULT` with feature/scenario/test-attempt/step evidence and no quality-gate fields.
- `artifact-manifest.schema.json` — bounded integrity metadata using opaque control-plane object references, never URLs.
- `live-event.schema.json` — five durable low-volume typed SSE projection events.
- `execution-command.schema.json` — implemented immutable semantic `ExecutionCommand` for one authorized assignment, persisted and returned to a worker. Not a broker message; nothing executes it.
- `sandbox-security-attestation.schema.json` — implemented evidence document carrying the hostile-execution gate's verdict from `services/runner` to the control plane, which cannot depend on that module.
- `mandatory-sandbox-controls.json` — not a schema. The single source of truth for the mandatory control set two modules must agree on and neither may import from the other; each side has a test asserting its own set equals this file.
- `docs/api/openapi-v1.yaml` — implemented authenticated control-plane APIs including Run create/get/list/snapshot, plus proposed cancellation/events/results/artifacts operations.

All JSON Schemas use Draft 2020-12, exact `1.0` versions, descriptions, bounded collections/strings, and closed objects with `additionalProperties:false` where appropriate. `ExecutionDispatch` is queue-time and contains no assignment or capability. `ExecutionCommand` is claim-time and remains proposed. Contract identities are independent of RabbitMQ, Docker, object-store URLs, or Java types.

## Validation

From the repository root:

```text
npm --prefix packages/api-contracts ci
npm --prefix packages/api-contracts test
npm --prefix packages/api-contracts run validate:schemas
npm --prefix packages/api-contracts run lint:openapi
```

The validator compiles all seven schemas with strict AJV 2020 plus standard formats; checks canonical/minimal valid fixtures and isolated negative categories; and applies named semantic checks for dispatch chronology/snapshot identity, result chronology, final-summary recomputation, cross-contract identity/epoch binding, uniqueness, contiguous test retry attempts, SSE progress/sequence, and aggregate fixture limits.

Runtime adapters must additionally enforce encoded/decoded bytes, nesting/decompression/node limits, authenticated producer binding, trusted tenant/run/attempt lookup, run version/assignment epoch/deadline, canonical path/archive safety, digest comparison, and storage verification. Schema validity alone is never acceptance.

## Version and compatibility policy

Schema selection uses a locally allowlisted exact `schemaVersion` and message/event type. Consumers never fetch a producer-supplied schema or remote `$ref`.

Closed objects and exhaustive enums make the policy intentionally conservative:

| Change | Classification for existing strict 1.0 consumers |
|---|---|
| Clarify descriptions without changing validation or meaning | Compatible |
| Loosen a numeric/string bound while preserving meaning | Conditionally compatible; producer rollout must still honor old bounds until all consumers upgrade |
| Add an optional property to a closed object | Breaking for old validators |
| Add an enum value/event/artifact/error category | Breaking for exhaustive consumers |
| Add a required property | Breaking |
| Remove or rename a property | Breaking |
| Tighten a bound or pattern | Breaking for previously valid producers |
| Change field meaning, identity, digest basis, ordering, or outcome semantics | Breaking |

Breaking changes require a new supported contract version and staged dual-read/new-write rollout: deploy readers for old+new, switch writers only after readers are ready, drain/retain old messages for their maximum lifetime, then remove old readers deliberately. A general-purpose schema registry is not introduced.

## ExecutionDispatch canonical digest

`payloadDigest` is a **semantic** digest, not a hash of the JSON bytes. It is reproducible in any language from the
rules below, so a consumer can verify integrity without agreeing on serializer behaviour.

Compute SHA-256 over the concatenation of the following values, each encoded as a four-byte **big-endian** length
prefix followed by its UTF-8 bytes, in exactly this order:

1. the literal format tag `kaas.execution-dispatch.v1`
2. `schemaVersion`
3. `messageId`
4. `messageType`
5. `dispatchId`
6. `occurredAt`
7. `producer`
8. `organizationId`
9. `projectId`
10. `runId`
11. `runVersion`
12. `attemptId`
13. `attemptNumber`
14. `runSnapshotId`
15. `runSnapshotDigest`
16. `queueDeadlineAt`

`payloadDigest` is excluded from its own input. Render the digest as `sha256:` followed by lowercase hex.

Normalization rules, which exist so that independent implementations agree:

- **Timestamps** are parsed and re-rendered as UTC with **exactly six** fractional digits and a trailing `Z`, for
  example `2026-08-28T12:00:00.000000Z`. Do not digest the wire form: `date-time` admits offsets, lowercase `t`/`z`,
  and a variable number of fractional digits, and language defaults differ. Six digits is PostgreSQL's `timestamptz`
  precision.
- **Integers** (`runVersion`, `attemptNumber`) use canonical decimal with no padding, no sign for non-negatives, and
  no separators.
- **Everything else** is digested exactly as it appears, including the `sha256:` prefix on `runSnapshotDigest`.

The length prefix makes the encoding prefix-free, so no field boundary can be forged by moving characters between
adjacent fields.

### Frozen test vector

The values below are the canonical fixture with whole-second timestamps — the case where a naive
`Instant.toString()`/`toISOString()` would disagree with a normalizing implementation.

```text
messageId          10000000-0000-4000-8000-000000000001
dispatchId         20000000-0000-4000-8000-000000000002
organizationId     30000000-0000-4000-8000-000000000003
projectId          40000000-0000-4000-8000-000000000004
runId              50000000-0000-4000-8000-000000000005
attemptId          60000000-0000-4000-8000-000000000006
runSnapshotId      50000000-0000-4000-8000-000000000005
runSnapshotDigest  sha256:7777777777777777777777777777777777777777777777777777777777777777
schemaVersion      1.0
messageType        EXECUTION_DISPATCH
producer           kaas.scheduler
runVersion         2
attemptNumber      1
occurredAt         2026-08-28T12:00:00Z   -> normalized 2026-08-28T12:00:00.000000Z
queueDeadlineAt    2026-08-28T12:05:00Z   -> normalized 2026-08-28T12:05:00.000000Z

payloadDigest      sha256:3d61597a9fa443ad54cf22e8bc2fa933e8ef13d883bfe6fb9c74b9b26a3b9b8f
```

Any implementation that does not reproduce that digest is not interoperable. Same message identity with the same
digest is an exact duplicate; the same identity with a different digest is an integrity conflict, never a redelivery.

## Security boundary

Contracts contain no raw secrets, provider paths, bearer capabilities, host filesystem paths, arbitrary storage URLs, Docker configuration, stack traces, credentials, or quality-gate claims from the runner. Opaque references grant no access by possession. HTML/log artifacts and all test-generated display text remain hostile content.

Execution remains disabled. These contracts do not replace authenticated transport, authorization, persistence constraints, sandboxing, egress controls, resource limits, secret isolation, artifact scanning, redaction, or tenant isolation.

## Canonical digest vectors

Two digests in this contract set are semantic canonicalizations rather than hashes of transmitted bytes, and a
second implementation cannot reproduce either from the schema alone. Both are length-prefixed: every component
is preceded by its UTF-8 byte length as a 4-byte big-endian integer, which is what makes the encoding injective
— without it `("ab","c")` and `("a","bc")` would share a digest.

### `kaas.network-policy.v1`

Covers the policy type and version, and nothing else. Identity is arbitrary and creation time is provenance.

```text
"kaas.network-policy.v1" | "POLICY_TYPE" | "DENY_ALL" | "POLICY_VERSION" | "1"
  => sha256:90bc5fe597d868eb21bc933950f31f10f4ea1f528e9e96a8eabdc7bd73a02450
```

That value is the one seeded by the V9 migration, and the control plane recomputes rather than trusts it: a row
edited underneath the application stops matching what it claims to be.

### `kaas.execution-command.v1`

Covers **every field the document emits**, excluding only the digest itself, in the fixed order enumerated by
`ExecutionCommandPolicy`. Collections are sorted before contributing and each is preceded by its count.

The rule matters more than the order: *a field the digest cannot cover must not be emitted*. An earlier design
excluded `commandId`, `issuedAt`, `expiresAt`, and the capability identifiers, and three independent reviews
produced collisions against it — including two commands binding one key to different secrets from different
providers. The source capability identifier was removed from the document rather than added to the digest,
because it rotates per delivery and could not be covered.

**This digest is not a signature.** It is unkeyed, so it establishes semantic identity and integrity against
partial or accidental modification, and confers no authenticity whatsoever.

### `kaas.source-bundle.v1`

Covers the sorted `(logicalPath, contentDigest)` pairs and their count. It deliberately does **not** cover the
ZIP framing, so a consumer verifies it by recanonicalizing what it extracted, never by hashing the archive it
received. The archive is nonetheless written deterministically.
