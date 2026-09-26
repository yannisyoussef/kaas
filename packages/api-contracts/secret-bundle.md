# Secret bundle (`POST /internal/v1/secret-bundles`)

The binary frame a secret capability redeems for. Produced by the control plane
(`SecretBundleFormat`), parsed independently by the runner (`SecretBundle`). Internal only: it is not
in the public OpenAPI document and is never routed from the public edge.

## Request

```
POST /internal/v1/secret-bundles
Authorization: Bearer <worker service token>
X-KaaS-Secret-Capability: kaas_sec_<43 url-safe base64 characters>
```

No body. The capability travels in its own header and never in a URL or query string. The worker's
service credential and the capability are both required: the capability is looked up by its SHA-256,
and the worker identity, run, attempt, assignment epoch, lease, authorization window, capability
window and pinned-version revocation state are all re-read under the run's row lock before anything is
decrypted, and re-read again after decryption and before anything is sent.

## Response: success

`200`, `Content-Type: application/octet-stream`, `Cache-Control: no-store`, `Pragma: no-cache`.

```
offset  size  field
0       8     magic        "KAASSEC1" (ASCII)
8       4     count        u32 big-endian, 1..50
then count entries, binding keys strictly ascending by byte order:
        2     keyLength    u16 big-endian, 1..128
        n     key          ASCII, ^[A-Za-z_][A-Za-z0-9_.-]{0,127}$
        4     valueLength  u32 big-endian, 1..8192
        n     value        UTF-8 bytes, exactly as stored
then:
        8     trailer      "KAASEND1" (ASCII)
```

Nothing follows the trailer. The sum of all `valueLength` is at most 65536.

The set of keys is EXACTLY the command's `secretBindings` keys: a receiver refuses a bundle with an
extra key, a missing key, a duplicate or unsorted key, a value that is empty, over the bound or not
strictly valid UTF-8, a short read, or trailing bytes. It never trims, merges or keeps "the part that
matched".

## Response: refusal

`409`, `Content-Type: application/json`, body `{"code":"<CATEGORY>"}` and nothing else. Categories:

| code | meaning |
| --- | --- |
| `CAPABILITY_INVALID` | wrong shape, unknown token, or a scope that does not equal the run's pinned set |
| `CAPABILITY_EXPIRED` | the capability or authorization window has closed, or the redemption ceiling was reached |
| `CAPABILITY_FENCED` | the run is not CLAIMED, the attempt is fenced or superseded, another worker holds it, or the lease lapsed — including during the provider call |
| `SECRET_VERSION_REVOKED` | a pinned version was revoked, before or during the call. No fallback to another version |
| `SECRET_VERSION_NOT_FOUND` | a pinned version has no ciphertext |
| `SECRET_PROVIDER_UNAVAILABLE` | Vault unreachable, slow, sealed or erroring |
| `SECRET_PROVIDER_AUTH_FAILED` | the control plane's AppRole login failed |
| `SECRET_ACCESS_DENIED` | Transit refused to decrypt under the tenant's context |
| `SECRET_VALUE_INVALID` / `SECRET_VALUE_TOO_LARGE` | a decrypted value is out of bounds |

A refusal is final for the worker: the run ends as an infrastructure failure before provisioning and
the engine never starts.

## Redemption semantics

At most **two** redemptions per secret capability (one delivery and one retry for a response lost in
transit), enforced by a `CHECK` on `execution_capabilities`, and at most **four** in total across every
secret capability issued under one authorization. A secret-free run is issued no secret capability.
