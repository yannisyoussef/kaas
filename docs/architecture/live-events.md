# Live Events and SSE

**Status: PROPOSED CONTRACT ARCHITECTURE.** The event schema is validated; no event store, publisher, authorization, or SSE endpoint is implemented.

## Durable event set

The MVP exposes only five bounded, durable, typed event kinds:

| Event | Purpose |
|---|---|
| `RUN_STATE_CHANGED` | New lifecycle/cancellation projection after a committed transition |
| `EXECUTION_STARTED` | Accepted worker start for the active attempt/epoch |
| `PROGRESS` | Coalesced logical-scenario progress, rate-limited by the producer |
| `ARTIFACT_AVAILABLE` | Artifact metadata after storage size/digest verification and policy checks |
| `EXECUTION_COMPLETED` | Canonical terminal outcomes after result processing |

Per-feature, per-scenario, per-step, and raw log events are intentionally excluded. They create amplification, retention, redaction, and browser-risk costs disproportionate to MVP value. Detailed steps remain in the structured result; logs remain bounded artifacts.

Every persisted event has UUID `eventId`, run identity/version, optional attempt identity, UTC occurrence time, exact type, closed payload, and a positive per-run `sequence`. The sequence is allocated atomically with the durable event and never depends on broker order.

## SSE endpoint

`GET /api/v1/runs/{runId}/events` requires authentication and run authorization on every initial and reconnect request. The proposed API supports bearer auth for fetch-based clients and a same-site Secure HttpOnly session cookie for native browser `EventSource`; tokens in query strings are forbidden. Cookie deployments must validate Origin/CORS and CSRF-relevant boundaries.

Wire mapping:

```text
id: <decimal sequence>
event: <eventType>
data: <one JSON RunLiveEvent>
```

The serializer, not event data, controls SSE field framing. CR/LF injection in IDs, event names, or data lines is impossible through raw concatenation.

## Resume and retention

- `Last-Event-ID` is an untrusted bounded decimal sequence and means “the last sequence fully processed.”
- The server replays retained events strictly greater than that value, then follows new committed events.
- No header replays from the earliest retained event appropriate to the initial subscription.
- A cursor ahead of the current head is rejected as validation failure.
- A cursor older than retained history returns `410 application/problem+json` **before** opening a stream. Extensions include safe earliest/latest sequence and a link to refetch run, results, and artifacts.
- Retention is bounded; the initial proposal is 24 hours after terminal completion, subject to capacity validation. Infinite replay is never promised.
- All sequenced events are durable for that window. There is no ephemeral event interleaved into the sequence, so ordinary reconnects do not manufacture gaps.

## Heartbeat, terminal, and cleanup

The server sends a comment heartbeat such as `: keep-alive` every 15 seconds while idle. It has no event ID, consumes no sequence, and is not replayed.

After the retained terminal event is delivered, the server closes the stream. If a reconnect cursor is already at or beyond that terminal event, `204` tells compatible clients not to reconnect. Connection lifetime is bounded (proposed maximum 15 minutes) so authentication/authorization revocation is re-evaluated. Disconnect releases only subscription resources; it never cancels the run or changes lifecycle.

## Limits and client behavior

Future implementation must cap streams per principal/organization, event rate, backlog bytes, and total connection lifetime. `PROGRESS` is coalesced and must satisfy completed ≤ total. One event is limited to 64 KiB decoded. Clients treat duplicate `eventId` or sequence as a no-op, reject schema versions they do not support, and refetch canonical resources after a gap.

The SSE stream is a convenience projection, not the system of record. `GET /runs/{id}`, `/results`, and `/artifacts` are authoritative resynchronization resources.
