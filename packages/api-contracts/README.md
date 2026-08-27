# API Contracts v1

Contracts are versioned independently from implementations. The initial contract defines stable vocabulary for control-plane APIs and the execution-plane boundary.

## Run creation

`POST /api/v1/projects/{projectId}/runs` accepts a profile plus an optional target and requires an `Idempotency-Key`. A repeated key with the same request returns the original run; reuse with a different request is a conflict.

## Error shape

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and optional `errors` field-level validation details.

## Pagination

Collection endpoints use `page`, `size` (bounded by server policy), and `sort`; responses contain `items` and `page` metadata.

## Runner boundary

The API sends a `RunnerCommand` containing an immutable feature revision reference, resolved non-secret configuration, secret references, timeout, and artifact policy. The runner returns a `RunnerResult` with execution outcome, structured results, and artifact metadata. Secret values never cross the contract boundary unless injected through a reviewed secret provider.
