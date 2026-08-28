# KaaS Contracts 1.0

**Status: MACHINE-VALIDATED PROPOSED ARCHITECTURE.** No runtime component publishes, consumes, stores, or serves these contracts.

## Contract set

- `runner-command.schema.json` — immutable `EXECUTION_COMMAND` for one fenced infrastructure attempt.
- `runner-result.schema.json` — structured `EXECUTION_RESULT` with feature/scenario/test-attempt/step evidence and no quality-gate fields.
- `artifact-manifest.schema.json` — bounded integrity metadata using opaque control-plane object references, never URLs.
- `live-event.schema.json` — five durable low-volume typed SSE projection events.
- `docs/api/openapi-v1.yaml` — proposed authenticated run creation/read/cancellation/events/results/artifacts API.

All JSON Schemas use Draft 2020-12, exact `1.0` versions, descriptions, bounded collections/strings, and closed objects with `additionalProperties:false` where appropriate. Contract identities are independent of RabbitMQ, Docker, object-store URLs, or Java types.

## Validation

From the repository root:

```text
npm --prefix packages/api-contracts ci
npm --prefix packages/api-contracts test
npm --prefix packages/api-contracts run validate:schemas
npm --prefix packages/api-contracts run lint:openapi
```

The validator compiles all four schemas with strict AJV 2020 plus standard formats; checks canonical/minimal valid fixtures and isolated negative categories; and applies named semantic checks for chronology, final-summary recomputation, cross-contract identity/epoch binding, uniqueness, contiguous test retry attempts, SSE progress/sequence, and aggregate fixture limits.

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

## Security boundary

Contracts contain no raw secrets, provider paths, bearer capabilities, host filesystem paths, arbitrary storage URLs, Docker configuration, stack traces, credentials, or quality-gate claims from the runner. Opaque references grant no access by possession. HTML/log artifacts and all test-generated display text remain hostile content.

Execution remains disabled. These contracts do not replace authenticated transport, authorization, persistence constraints, sandboxing, egress controls, resource limits, secret isolation, artifact scanning, redaction, or tenant isolation.
