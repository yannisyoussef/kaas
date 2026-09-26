# Tenant secret execution

How a tenant's secret travels from the tenant to the Karate engine and what the platform keeps of it on the way.
Decided by [ADR-034](../adr/034-assignment-scoped-secrets-vault-transit.md). The byte-level formats are in
`packages/api-contracts/secret-bundle.md` and `packages/api-contracts/engine-frame.md`.

## Trust boundaries

| Party | Holds | Never holds |
| --- | --- | --- |
| Tenant (API caller) | its own plaintext, when writing a version | any ciphertext, any other tenant's metadata |
| Control plane | AppRole credentials, a short-lived Vault token, ciphertext, and plaintext **only** while writing a version or serving one redemption | plaintext at rest, a hash of plaintext, a cache of plaintext |
| Vault Transit | the key | any tenant data (Transit stores nothing) |
| PostgreSQL | version metadata, ciphertext, revocations, capability hashes, pinned (reference, version) | plaintext, plaintext hashes, lengths, Transit context |
| Runner | the capability token (per delivery), the plaintext of one run's pinned set for one execution | AppRole, Vault token, any route to Vault, another run's values |
| Sandbox / Karate JVM | the run's values, via `kaas.secrets.<KEY>` | AppRole, Vault token, the runner's service credential |
| Egress proxy | the execution's egress capability per request | any secret value, any registry of values |

## Plaintext lifetime

```
tenant ── POST .../versions (octet-stream, TLS at the edge) ──▶ control plane
    request buffer (bounded read, 8 KiB)          cleared after encrypt
    Transit encrypt request body (base64)         cleared after send
        ──HTTPS, CA-pinned──▶ Vault Transit encrypt (context org:<o>/project:<p>)
    ciphertext ──▶ PostgreSQL secret_version_ciphertexts        (no plaintext persisted)

run creation: pins (reference, version) from metadata; nothing decrypted
authorization: issues SECRET capability scoped to the pinned set; nothing decrypted

runner ── POST /internal/v1/secret-bundles (worker credential + X-KaaS-Secret-Capability) ──▶ control plane
    authority revalidated under the run lock; scope == snapshot; revocation checked
    ──HTTPS──▶ Vault Transit decrypt, per value, under the owning row's context (bounded timeout)
    decrypted byte[] (control-plane memory)       cleared on every path
    authority revalidated again
    secret bundle frame (binary)                  written once, then cleared
        ──internal transport (WireGuard in production)──▶ runner
    bundle byte[] → parsed, exact key set checked against the command    frame cleared
    EngineInput: engine frame + redactor copies  cleared (close) after the sandbox
        ──container stdin, after the source frame──▶ sandbox
    bootstrap: reads the source frame only; freezes; drops every capability; execs the adapter
    adapter: reads the engine frame; requires EOF of the frame; closes fd 0; kaas.secrets=CONSUMED
    Karate JVM: kaas.secrets.<KEY> (a Java String — cannot be cleared)
        ──tenant HTTP request──▶ egress proxy (policy check) ──▶ authorized destination

sandbox output ──live attach (LogConfig=none; the daemon keeps nothing)──▶ runner collector
    raw frames → protocol branch (counts, vocabulary words) / redactor → ceiling → decode → kept transcript
```

At every "cleared", the code overwrites the array it owns. That is best effort and is stated as such: the JVM
copies buffers (strings, HTTP stacks, TLS records, Karate's own objects), and none of those copies is reachable
from this code. No memory zeroisation guarantee is made.

## Where plaintext can and cannot be

| Surface | Control plane | Runner | Sandbox |
| --- | --- | --- | --- |
| Memory | yes, during write / one redemption | yes, during one execution | yes, by design |
| File | no | no (no staging directory, no temp file) | no (engine frame is read from a pipe) |
| Environment | no | no | no — measured: `/proc/self/environ` |
| argv / cmdline | no | no | no — measured: `/proc/self/cmdline` |
| JVM system properties | no | no | no — measured |
| Queue / outbox / dispatch | no — measured | — | — |
| Database | ciphertext only — measured across every text/JSON/bytea column | — | — |
| Logs / metrics | no (categories and identifiers only) | no | output is redacted before it is kept |
| Container metadata | — | no — measured while the container runs | — |
| Docker log store | — | none exists (`LogConfig=none`) — measured | — |
| Test reports | no — scanned by nonce in CI | no | — |
| Core / heap dumps | — | — | disabled (JVM flags + core ulimit 0) |

## Failure semantics

A secret-bearing run whose values cannot be obtained never starts its engine. The run fails as an
infrastructure failure with a category (`SECRET_VERSION_REVOKED`, `SECRET_VERSION_NOT_FOUND`,
`SECRET_PROVIDER_UNAVAILABLE`, `SECRET_PROVIDER_AUTH_FAILED`, `SECRET_ACCESS_DENIED`, `SECRET_VALUE_INVALID`,
`SECRET_VALUE_TOO_LARGE`, `CAPABILITY_FENCED`, `CAPABILITY_EXPIRED`, `CAPABILITY_INVALID`,
`BUNDLE_UNEXPECTED_SET`, …): test outcome not available, infrastructure outcome FAILED. All or nothing: one
unresolvable binding refuses the whole set.

A tenant assertion that fails because of a secret — including one whose message quotes it — is a **test
failure** (FAILED) on a successful infrastructure, and the quoted value is redacted from what is kept.

## Authority races

| When authority is lost | What happens |
| --- | --- |
| before redemption | the runner does not ask; the control plane refuses without calling Vault |
| during the Vault call | decryption completes, the post-call revalidation refuses, plaintext is cleared, nothing is sent (tested) |
| after the response, before delivery | the runner re-checks authority before framing; the sandbox is not created |
| during delivery / after engine start | KAAS-16's continuous authority stops the sandbox; the JVM holding the values dies with it; no result is submitted |
| a version revoked in the instant between the post-call check and the response write | **not caught**: the value is delivered, and a sandbox that holds a value keeps it until it ends. Revocation stops every later redemption and every new run; it does not reach into a running JVM (ADR-034, residual risks) |

## Limits

Bounded at every boundary: 50 secrets per run, 8 KiB per value, 64 KiB per run — at write, after decryption,
when the bundle is built, and again by the runner's parser.
