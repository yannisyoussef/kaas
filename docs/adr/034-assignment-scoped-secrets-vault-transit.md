# ADR-034: Assignment-scoped secrets with Vault Transit envelope encryption

**Status: ACCEPTED.** Authorizes secret-bearing Karate 2.1.2 execution: a run may bind tenant secrets, the
platform resolves exactly the pinned versions for exactly one live assignment, delivers them into the mediated
sandbox after the source filesystem is frozen and every capability dropped, and removes their exact byte
sequences from everything it keeps of the sandbox's output. Re-adjudicates ADR-033's "zero secret bindings"
restriction for Karate. Does not authorize a second engine, persisted tenant output, reports, artifacts, or
production deployment of secrets (see *Deployment prerequisites*).

## Context

ADR-015 made SecretReferences metadata-only because no provider existed; ADR-023 modelled a secret capability
and never issued one; ADR-033 authorized tenant code only for runs binding nothing. The takeover audit found the
provider decision was the only blocker that engineering could not make.

The independent Operations review made it:

- An **existing Vault** is used **only as a key service** (Transit), never as a secret store (KV).
- Tenant secret ciphertext lives in **KaaS PostgreSQL**.
- Transit key `kaas-tenant-secrets`: `aes256-gcm96`, **derived**, non-exportable, deletion disallowed.
- Every encrypt and decrypt carries the context `org:<organizationUuid>/project:<projectUuid>`.
- Configuration: `KAAS_VAULT_ADDR`, `KAAS_VAULT_ROLE_ID`, `KAAS_VAULT_SECRET_ID`, `KAAS_VAULT_CACERT`,
  `KAAS_VAULT_TRANSIT_KEY`. Deployment endpoint `https://vault:8200`, CA `/run/kaas/vault-ca.pem` — neither
  hard-coded.
- **Only the control plane** holds AppRole credentials. The runner, sandbox, proxy and engine hold none and
  have no route to Vault.
- Runner-to-API traffic will travel inside WireGuard (`http://10.77.0.1:8443`); `/internal/**` is never routed
  from the public edge. Neither replaces application authentication.
- **Pinned versions**: a run executes the version it was created with, never "latest".
- **Fail-closed emergency revocation**: `SECRET_VERSION_REVOKED`, no fallback.

## Decision

### Vault is a KMS; PostgreSQL holds ciphertext

Why not Vault KV or OpenBao KV: the platform would then read secrets by path at execution time, which makes
"what a run executes with" a property of a mutable external store rather than of the run; every run would need
a policy path per tenant, and version pinning would depend on KV's own history semantics and retention. Keeping
ciphertext beside the metadata makes a version an immutable row the snapshot can reference by foreign key, lets
revocation be a transactional database operation, and keeps Vault stateless for KaaS: it holds keys, never
tenant data.

Why derived keys: one Transit key serves every tenant, and Vault derives a separate data key per context. A
ciphertext presented under another project's context fails authentication in Transit itself — measured, in
both directions, including by Vault's own operator path. Tenant isolation is therefore cryptographic as well as
a property of the SQL that looks ciphertext up. The context is built only from the owning row's UUIDs; there is
no input from which a tenant could choose it.

The Transit key is **never deleted**: it is shared across tenants and versions, and deleting it would destroy
every tenant's data rather than one version's.

### Immutable versions, pinned by runs

- `secret_versions` (immutable, consecutive numbering enforced by trigger), `secret_version_ciphertexts`
  (the only destroyable state), `secret_version_revocations` (append-only).
- A run pins `(reference, version)` at creation from metadata; nothing is decrypted to create a run. The snapshot
  digest covers the version; a secret-free snapshot digests exactly as before (checked against the pre-KAAS-22
  code, not assumed).
- Rotation writes version N+1. Runs pinned to N keep N. **Tenant secret rotation and Transit key rotation are
  different operations**: a Transit rotation changes which key version encrypts new values; existing ciphertext
  stays decryptable until Operations raises `min_decryption_version`. No background rewrap exists in this slice.

### Revocation is metadata that blocks and a deletion that shreds

Revocation inserts an append-only record **and** deletes the version's ciphertext in the same transaction. The
version row stays, immutable, as audit evidence; the payload is gone, so there is nothing any key could decrypt
(crypto-shredding without deleting the shared key). Every authorization and every redemption checks revocation
live; a revoked pinned version fails `SECRET_VERSION_REVOKED`, before any decryption, with no advance to another
version.

### The write path is the only place plaintext enters

`POST /api/v1/projects/{p}/secret-references/{r}/versions`, `application/octet-stream`, 1–8192 bytes of strict
UTF-8, exact bytes (no trim, no normalisation). Encrypted, stored as ciphertext, cleared. Never echoed, logged,
counted, or fingerprinted — so, unlike every other create in this API, it is **not idempotent**: an idempotency
record would be a hash of the plaintext, which the schema refuses to hold. There is no endpoint that returns a
value, a ciphertext or a history of values.

### One capability, the exact set, twice at most

Authorization issues a `SECRET` capability whose scope is exactly the snapshot's pinned `(key, reference,
version)` triples — **never empty** (a secret-free run receives no secret capability at all) and never a
wildcard. The command's `secretBindings` names the same set and is digested by both sides.

`POST /internal/v1/secret-bundles` (worker credential **and** the capability header) re-reads authority under the
run lock, compares the scope with the snapshot exactly, checks revocation, then decrypts **outside** the lock with
a bounded timeout, then re-reads authority again before responding. All values or none; the response is a binary
frame. Redemption is bounded at **two** per capability (delivery plus one retry for a lost response, enforced by
a CHECK) and **four** per authorization across rotations. Unlimited replay is impossible.

A secret-free run never contacts Vault: not its configuration, not its health. A sealed or absent provider
cannot stop secret-free execution (tested with the provider sealed).

### Runtime-only injection: after the freeze, over the same one-shot channel

The runner writes an **engine frame** immediately after the source frame on the sandbox's standard input. The
bootstrap reads exactly the source frame with unbuffered reads, freezes the source filesystem, drops every
capability and execs the adapter; the privileged program never reads a secret byte. The adapter reads exactly
the engine frame, requires the pipe empty, closes descriptor 0 (the JVM re-points it at `/dev/null`), prints
`kaas.secrets=CONSUMED`, and only then starts Karate with a programmatic global: `kaas.secrets.<KEY>`.

Not environment variables, JVM properties, argv, files, Docker secrets, or source templating — each measured
absent from inside the running engine and from the daemon's side. The value is intentionally readable by tenant
code; Java visibility is not claimed as a protection.

Every tenant run gets an engine frame, secret-free or not, so descriptor 0 is closed for all of them.

### The daemon keeps nothing; the trusted collector decides what is kept

Tenant-code sandboxes run with `LogConfig=none` and are read over a live attach. Before this, a feature's output
was written by the daemon to a host json-file before any trusted code had seen it. Platform-only probe
sandboxes keep the bounded json-file introduced in KAAS-13.

In the runner, the raw stream splits:

- a **protocol branch** reads the adapter's four lines from raw bytes, keeping only counts and closed-vocabulary
  words, so a secret equal to `PASSED` cannot change a verdict and a malformed line is never kept;
- a **redactor** per stream replaces every exact occurrence of every delivered value (and the egress
  credential) with `[REDACTED]`, across arbitrary frame boundaries, **before** the output ceiling, so truncation
  can never leave a prefix of a value;
- then incremental UTF-8 decoding, line assembly, and sanitisation.

The runner itself now enforces the engine identity (`karate 2.1.2`) and the secret-channel confirmation, each
exactly once. ADR-033's duplicate rule covers all three keys.

### Karate behind the allowlist

The engine frame also carries the execution's proxy endpoint and egress capability; the platform's classpath
`karate-config.js` points Karate's HTTP client at the proxy. The proxy accepts the capability as a Basic
password in addition to Bearer (the engine's HTTP client authenticates only by answering a Basic challenge),
checks it against the control plane exactly as before, and still logs nothing a request carries.

### Crash posture

Secret-bearing engines run with `-XX:-HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError
-XX:-CreateCoredumpOnCrash -XX:ErrorFile=/tmp/hs_err.log` and a core ulimit of 0; `/tmp` is the sandbox's own
bounded, non-executable tmpfs.

## Guarantee, and what is not guaranteed

KaaS guarantees that **exact known raw secret byte sequences** are removed from the platform-owned output it
keeps (the runner's transcripts and observations, and everything built from them), that no secret is persisted
in the database, the outbox or dispatch payloads, container metadata, Docker's log store, runner host files, or
test reports, and that secrets reach tenant code only through the authorized channel.

KaaS does **not** detect transformed values: Base64, hashes, reversal, character splitting, custom encoding or
encryption, or a value printed one character per line. Hostile tenant code that is authorized to reach a
destination may send a secret there. Network policy controls **where** a sandbox may send data; redaction
controls **what KaaS itself keeps**. This is not data-loss prevention.

## Consequences

- ADR-033's refusal `ENGINE_REQUIRES_SECRET_FREE_RUN` now applies to the synthetic workload only.
- The command contract's `secretCapabilities` (never populated) is replaced by `secretBindings`; the delivery
  envelope carries `secretCapabilityToken` only for a secret-bearing run.
- `secret-execution-gate` is the tenth mandatory CI job. It runs under runsc with a real Vault.
- Migration V13 is expand-only; the previous release keeps working against it. A run created by that release
  pins no version and is refused (`RUN_SNAPSHOT_INVALID`) rather than executed against a version chosen later.

### What the key must be, checked rather than assumed

The per-project context only separates tenants if the Transit key is **derived**; Vault accepts and ignores a
context on a key that is not. So the control plane reads the key's configuration with every fresh AppRole token,
before that token is used or cached, and refuses a key that is not `derived`, or that is `convergent`,
`exportable` or `deletion_allowed` (`SECRET_PROVIDER_UNAVAILABLE`). This is checked at login, not at startup, so a
control plane with Vault down still starts and still runs secret-free work. **The AppRole policy therefore needs
`read` on `transit/keys/kaas-tenant-secrets`** in addition to `update` on encrypt and decrypt.

### Which version a NEW run pins

A new run pins the highest version that is **not revoked**. Revoking the newest version therefore makes new runs
pin the one before it — deliberately: revocation retires a value, it does not delete a secret. To retire a value
from every future run, revoke every version that holds it; to stop a secret being used at all, remove the
binding. A run already pinned is never moved: "no fallback" above is about pinned runs.

### Transport of plaintext to the runner

The runner redeems secrets only over `https`, or plain `http` to its own loopback interface; any other address
is refused before a request is made. The source bundle has always travelled over whatever address the runner is
configured with; secrets do not.

## Deployment prerequisites (not satisfied by this ADR)

Vault backup, audit device and seal alerting; the dedicated staging execution host; WireGuard; the KaaS stack;
log-shipper exclusion of sandbox containers; monitoring and backups; a production runner daemon (the loop is
still composed only by tests — see the slice report); and a staging rehearsal. **Application capability is not
deployment readiness.**

## Residual risks

- `source_mount_nodev=false` and `java_threads_bounded=false` remain as accepted in ADR-032/033.
- Plaintext exists in managed-runtime memory (control plane, runner, engine) for the duration of one execution;
  buffers the code owns are cleared best-effort, and copies the JDK, the HTTP stack or Karate make cannot be.
- Transformed exfiltration, and exfiltration to an authorized destination, are out of scope by design.
- The egress capability is readable by tenant code (as it always was for the platform's own workload); it is
  scoped to one execution's policy and revalidated per request. It is not in the engine container's
  environment (the engine never reads one), so it is not in container metadata either.
- **A revocation that commits in the last instant of a redemption is still delivered.** Authority and version
  state are re-read after the Vault call, but a revocation committing between that check and the socket write is
  not seen, and a sandbox that already holds a value keeps it until it ends. Revocation stops every *later*
  redemption and every new run; it does not reach into a running JVM.
- **The verdict remains tenant-controlled, by design.** A tenant that prints a forged `PASSED` and then exits
  the JVM before the adapter runs, or that replaces `System.out` and so controls every byte the adapter prints,
  is not stopped: the adapter shares a JVM with tenant code, so no secret it holds could authenticate its line.
  This is the tenant choosing its own outcome, which it can also do by writing a test that always passes. The
  narrower trick of leaving stdout mid-line to swallow the verdict does not work under Karate 2.1.2 (its console
  summary ends the open line first, measured); the adapter also starts its verdict with a newline so that
  does not depend on the engine.
- **The engine image has no SLF4J provider** (measured: SLF4J falls back to its no-op logger), so Karate's
  HTTP client logs nothing, including request headers. Adding a logging backend to the engine would change that
  and would need the encoded-forms decision below first.
- **Encoded forms are not redacted**, including ones a tenant's HTTP client produces unprompted: an
  `Authorization: Basic` header carries `base64(user:password)`, a JSON body escapes a PEM's newlines, a URL
  percent-encodes. The one encoding the *platform* introduces — its proxy credential as
  `Proxy-Authorization: Basic base64("kaas:" + token)` — is registered with the redactor. Before transcripts
  are ever persisted, the others need a decision.
