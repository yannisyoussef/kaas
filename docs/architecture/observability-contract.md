# Observability Contract

**Status: PROPOSED SEMANTICS.** No OpenTelemetry dependency, collector, instrumentation, dashboard, or alert is implemented.

## Trace propagation

- HTTP accepts and returns standard W3C Trace Context through `traceparent` and optional `tracestate`.
- Messaging transports those values as technical headers, not JSON business fields.
- Outbox records may retain validated bounded trace context as technical metadata so asynchronous publishing continues the trace.
- Consumers validate/normalize context and start a consumer span linked or parented according to instrumentation semantics.
- Arbitrary baggage is not propagated across the execution boundary. Secrets, variables, feature content, URLs, and credentials are never trace context.

Trace context is diagnostic and never used for authorization, tenant selection, idempotency, ordering, or lifecycle decisions.

## Structured logs

Where available and relevant, logs use stable fields:

`timestamp`, `level`, `service`, `operation`, `traceId`, `spanId`, `organizationId`, `projectId`, `runId`, `attemptId`, `assignmentEpoch`, `messageId`, `commandId`, `resultId`, `lifecycleState`, `testOutcome`, `infrastructureOutcome`, `errorCode`.

Not every event carries every field. IDs are derived from trusted context after binding, not copied blindly from an untrusted payload. Log messages and attributes must not include:

- secrets, capability tokens/references, authorization headers, cookies, or credentials;
- feature/scenario/step text, tags, examples, test variables, HTTP payloads, or raw test output;
- host paths, object references, bucket keys, presigned URLs, stack traces, or artifact/log contents.

Sanitized error codes and opaque diagnostic references are preferable to raw internals. DLQ and reconciliation logs follow the same redaction rules.

## Metrics and cardinality

Allowed bounded metric dimensions include:

- service/operation;
- message type and schema major;
- lifecycle state and transition;
- test/infrastructure outcome;
- error category/phase/code from an allowlist;
- HTTP status class;
- engine type/version family;
- worker pool/region; and
- retry/DLQ disposition.

The following **must not** be metric dimensions: trace/span ID, organization/tenant/project/run/attempt/assignment/message/command/result/artifact ID, feature/revision/scenario/step name or ID, tag, policy version, path, URL, host, object reference, secret reference, or user-entered value. Trace IDs may appear in exemplars and spans, not labels.

## Initial measurements

- run acceptance and idempotency replay/conflict latency;
- queue, provisioning, execution, result-collection, result-processing, cancellation, and total durations;
- active/leased/stopping attempts by bounded worker pool;
- lease expiry, stale message, dedupe, retry, DLQ, and reconciliation counts;
- terminal outcomes and quality-gate statuses;
- rejected command/result/manifest/event bytes by bounded reason;
- artifact bytes accepted/rejected and verification/scan duration; and
- SSE active connections, reconnects, replayed events, and retention gaps.

Run and attempt duration belongs in histograms; per-run progress never becomes a metric series.

## Security and operational constraints

Telemetry exporters are lower-trust external integrations. Apply authenticated transport, least privilege, retention policy, sampling/cost bounds, field allowlists, and redaction tests. Test-generated content is hostile and must not control metric names, labels, span names, log keys, or event framing.

Open questions for implementation validation include trace sampling and cost, collector failure behavior, authenticated broker propagation, auth-revocation visibility, and whether diagnostic references need a separate audited retrieval API. These semantics do not claim telemetry is operating.
