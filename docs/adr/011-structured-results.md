# ADR-011: Structured execution evidence with separate artifact manifests

## Status

PROPOSED

## Context

Opaque scenario JSON, arbitrary error strings, and report-only artifacts cannot support trustworthy APIs, quality gates, compatibility, or security review. Karate outlines, hooks, backgrounds, and scenario retries need deterministic representation without exposing runtime internals. No engine integration or result persistence exists.

## Decision

Use a strict versioned `ExecutionResult` hierarchy of feature → logical scenario → test-level scenario attempts → step results. Preserve deterministic outline row identity, explicit background/hook kinds, bounded structured test/infrastructure errors, nested recomputable summaries, separate test/infrastructure outcomes, and result completeness.

Keep quality-gate evaluation out of runner output. Store artifact bytes outside the result and exchange a separate strict manifest containing tenant/run/attempt binding, type, size, exact-byte SHA-256, and opaque control-plane object references. Treat HTML and logs as hostile content.

## Alternatives considered

- Persist only Karate HTML/JSON reports.
- Store arbitrary engine-native JSON under scenarios.
- Flatten all results into log events.
- Let the runner evaluate quality gates and return URLs.

## Why alternatives were rejected

Reports and native JSON are engine/runtime formats rather than stable product contracts. Arbitrary payloads defeat schema validation and leak internals. Event streams are unsuitable as the canonical result. Runner-owned gates couple policy to execution, while URLs and broad storage credentials cross the trust boundary.

## Consequences

### Positive

- Product/API behavior can depend on stable structured evidence.
- Test retry, outline, hook, error, and artifact integrity are explicit.
- Strict schemas and negative fixtures reject leakage fields and malformed structures.

### Negative

- Engine mapping and semantic recomputation require careful implementation tests.
- Strict closed schemas make even optional-field and enum additions breaking to old validators.
- Result volume requires aggregate limits beyond schema cardinality.

### Neutral

- Earlier failed scenario retries remain evidence but final logical attempts drive summaries.

## Validation and revisit conditions

Validate against representative Karate outputs for plain scenarios, outlines with multiple Examples blocks, hooks, backgrounds, retries, skips, aborts, assertion/script errors, partial infrastructure failure, and parallel duration semantics. Revisit only through an explicit versioned compatibility plan.
