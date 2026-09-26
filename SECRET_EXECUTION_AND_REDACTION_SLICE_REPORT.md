# KAAS-22 — Secret execution and redaction: slice report

Assignment-scoped secret execution, Vault Transit envelope encryption, immutable secret versions, runtime-only
delivery, trusted streaming redaction, and secret-bearing Karate. Adjudicated by
[ADR-034](docs/adr/034-assignment-scoped-secrets-vault-transit.md).

**Application capability is not deployment readiness.** Nothing in this report claims KaaS is deployable.

## 1. Executive summary

A tenant can now write a secret, bind it into a run profile, and have Karate 2.1.2 use it inside the gVisor
sandbox, while KaaS keeps neither the plaintext nor any trace of it:

- Plaintext goes to Vault Transit once on write. KaaS PostgreSQL stores only the Transit ciphertext, under a
  derived key whose context is `org:<uuid>/project:<uuid>`.
- A run pins the exact version it was created with. Rotation writes a new version; revocation fails pinned runs
  closed and shreds the ciphertext.
- The runner redeems exactly the pinned set, once, through a SECRET capability, and delivers it to the engine
  over stdin after the source filesystem is frozen and every privilege is dropped.
- Everything the sandbox prints passes a trusted streaming redactor before anything is kept. The sandbox has no
  Docker log store at all.

Independent reviews found no P0. One reviewer P1 (verdict forgery) was measured and reclassified (§52); the other
review P1s were harness and test-gate defects and are fixed. Every P2 is fixed or recorded as a residual. The
mutation battery (§53) killed every mutant but one equivalent mutant, after one real survivor (K22-17) exposed a
fail-open leak scan, which is now fixed and its mutant killed.

**CI status:** the final SHA `5ecf07a` passed all ten CI jobs in run **36274892142**, with complete secret-gate
evidence under runsc (§56, §63). The implementation had passed earlier as `287c45e` (run 36269755525); the final
run covers the review fixes made after it.

## 2. Starting commit

`d1ad2fff3f0fa5cd46f5a9c26d8c7c58da95382d` on `codex/project-feature-control-plane`, KAAS-21 (secret-free Karate)
complete, nine CI jobs. Verified clean at the start; no partial KAAS-22 work existed.

## 3. Takeover findings

- The suspected cross-project read in `ConfigurationService.getSecretReference` was **not reachable**: the SQL was
  already scoped by organization and project. `requireProject` was added anyway so the service refuses before the
  query, and a regression test pins it (K22-30 kills the SQL-side scoping).
- The previous `SecretValueProvider` / `UnavailableSecretValueProvider` scaffolding was never on a live path and
  was deleted rather than adapted.

## 4. Ops contract

Existing Vault 1.18.5, used **only** as a Transit KMS, not KV. Key `kaas-tenant-secrets`, `aes256-gcm96`,
`derived=true`, not exportable, deletion not allowed. AppRole for the control plane only. Configuration by five
variables — `KAAS_VAULT_ADDR`, `KAAS_VAULT_ROLE_ID`, `KAAS_VAULT_SECRET_ID`, `KAAS_VAULT_CACERT`,
`KAAS_VAULT_TRANSIT_KEY` — all or none; a partial set refuses to start. **New for Ops (review finding):** the
AppRole policy needs `read` on `transit/keys/kaas-tenant-secrets`, because the control plane verifies the key's
properties with every token (§8).

## 5. Scope / non-goals

In: secret write/list/revoke API, versions, Transit, pinning, SECRET capability, redemption, runtime delivery,
redaction, secret-bearing Karate, the tenth CI gate, docs. Out: deployment (Vault install, runsc install,
WireGuard, the KaaS stack, OVH), a production runner daemon, persistence of transcripts, detection of transformed
values, KAAS-23.

## 6. Secret threat model

Tenant code is fully hostile but is also the **owner** of the secret it is given; the question is not whether
the tenant can read its own secret but whether KaaS keeps it, leaks it elsewhere, or hands it to anyone else.
Adversaries considered: another tenant (cross-project decryption, capability reuse), another worker or a stale
assignment epoch, a compromised runner (asking for arbitrary secrets), the platform's own logging and
persistence (DB, outbox/RabbitMQ, Docker log store, container metadata, host files, test reports, proxy logs),
and the tenant's own code trying to make the platform keep or mis-attribute a value.

## 7. Vault Transit choice

Transit keeps Vault a key service: KaaS owns version semantics, pinning and revocation in its own transactional
schema, and Vault never stores tenant data. KV would have made version pinning depend on KV history retention and
needed a policy path per tenant (ADR-034 §"Vault is a KMS").

## 8. Transit authentication

AppRole login with a runtime-supplied role id and secret id; the token is held in memory, reused for at most three
quarters of its lease (capped at 20 minutes), and re-obtained once on a 403. TLS trusts exactly the configured CA
(no JDK trust store), hostname verification untouched, redirects disabled, no Vault SDK. **Added after review:**
each fresh token first reads the key configuration and the client refuses a key that is not `derived`, or is
`convergent_encryption`, `exportable` or `deletion_allowed` (`SECRET_PROVIDER_UNAVAILABLE`). Checked at login, not
at startup, so a control plane with Vault down still starts and runs secret-free work.

## 9. Transit context

`org:<uuid>/project:<uuid>`, lowercase, built only from the owning row's ownership columns, never from request
input. With a derived key the context selects a per-project key; decrypting under another project's context
fails the authentication tag (tested against real Vault, including a sibling project in the same organization).

## 10. SecretReference model

Unchanged metadata (`secret_references`): name, project, creator. Reads are project-scoped in SQL and in the
service (§3).

## 11. SecretVersion model

`secret_versions`: consecutive per reference (trigger + row lock + UNIQUE), immutable. Ciphertext lives in a
separate `secret_version_ciphertexts` table so it can be destroyed without touching version history.
Revocations are an append-only `secret_version_revocations` table and cannot be undone.

## 12. Ciphertext persistence

Only the Transit ciphertext: shape CHECK `^vault:v[1-9][0-9]{0,6}:[A-Za-z0-9+/]+={0,2}$`, at most 16 384 bytes, no
UPDATE, DELETE only once a revocation exists. No plaintext, no plaintext hash, no length, no context is stored.

## 13. Version pinning

A new run pins, per binding, the highest version that is not revoked, into
`run_snapshot_configuration_entries.secret_version_number` (sealed by the V3 immutability trigger). The snapshot
digest covers the version (`SECRET_VERSION`); secret-free snapshot digests are byte-identical to before
(golden `sha256:9828b56d…` verified against the pre-change code).

## 14. Rotation

Writing a value creates the next version. Pinned runs keep their version; new runs pin the new one. Rotating the
Transit key is invisible to tenants: old ciphertext still decrypts, new ciphertext uses the new key version
(tested).

## 15. Revocation

`POST …/versions/{n}/revocation` inserts the revocation and deletes the ciphertext in one transaction. A run pinned
to it is refused with `SECRET_VERSION_REVOKED` at authorization, at redemption, and after the decrypt, and never
advances to another version. New runs pin the highest non-revoked version (ADR-034 now states this explicitly:
revoke every version holding a value to retire it from future runs).

## 16. Migration compatibility

V13 is expand-only. The previous release keeps working against it; a run it creates pins no version and is refused
(`RUN_SNAPSHOT_INVALID`) rather than resolved later. `MigrationUpgradeTests` now seeds a pre-V13 secret-binding
snapshot row and a STRING row, so V13's new CHECK and foreign key are validated against real rows (K22-40, a CHECK
that forgets `IS NULL`, is killed by it). `execution_capability_secret_references` was provably empty (no SECRET
capability was ever minted before V13), so its NOT NULL and PK change are safe.

## 17. Secret write API

`POST /api/v1/projects/{p}/secret-references/{r}/versions` (octet-stream, strict UTF-8, ≤ 8 KiB), `GET` list
(metadata only), `POST …/{version}/revocation`. Responses never echo a value; `Cache-Control: no-store`.
Encryption happens outside the database transaction; no idempotency key (a retried write is a new version).

## 18. SecretCapability

Minted at authorization only for a secret-bearing run; a secret-free run receives no secret token at all. Scope
rows name every (binding key, reference, version) triple the snapshot pinned — never a wildcard, never "latest".

## 19. Exact set

Redemption requires the capability's scope to equal the snapshot's pinned set exactly
(`SecretCapabilityService.exactlyTheSnapshot`): an extra, a missing, a different version, a different reference,
a duplicate key, an empty set and an unpinned (NULL) version are all refused (unit-tested directly because no
integration path can produce a disagreement; K22-08). The runner independently refuses a bundle whose keys differ
from the command's (K22-10).

## 20. Redemption semantics

All or nothing; at most 2 redemptions per capability (CHECK-backed) and 4 per authorization (under the run lock).
Authority is checked under the run lock before the decrypt, the decrypt runs outside the lock, and authority,
capability revocation and version state are re-read under the lock afterwards (K22-27). A redemption consumed by a
provider failure counts (conservative).

## 21. Internal endpoint

`POST /internal/v1/secret-bundles`, capability in `X-KaaS-Secret-Capability` (never the URL), worker identity from
the authenticated principal. Response: the binary `KAASSEC1` frame written synchronously to the servlet response
and then cleared (`packages/api-contracts/secret-bundle.md`).

## 22. Transport assumption

**Added after review:** the runner redeems secrets only over `https`, or `http` to its own loopback; any other
address is refused before a request exists (K22-42). Production must therefore put TLS between runner and control
plane — a deployment prerequisite, not something this slice deploys.

## 23. Provider errors

Categories only: `SECRET_PROVIDER_UNAVAILABLE`, `SECRET_PROVIDER_AUTH_FAILED`, `SECRET_ACCESS_DENIED`,
`SECRET_VALUE_INVALID`, `SECRET_VALUE_TOO_LARGE`, `SECRET_VERSION_REVOKED`, `SECRET_VERSION_NOT_FOUND`,
`SECRET_DELIVERY_FAILED`. `SecretProviderException` has no cause, no message and no stack trace; Vault response
bodies are never read into an exception or a log. The API's exception handler logs no cause chain on secret paths
(now matched anywhere in the URI so a servlet context path cannot disable it).

## 24. Plaintext lifetime

Write request body → Transit encrypt → cleared. Redemption: Transit decrypt → bundle frame → HTTP response →
cleared. Runner: response bytes → parsed bundle → engine frame → stdin → cleared on every path. Engine: read from
stdin into the JVM, exposed to Karate as `kaas.secrets`, dies with the sandbox. Byte buffers the code owns are
sized so they never grow (a grown buffer leaves an uncleared copy) and are zeroed; copies made by the JDK, the HTTP
stack or Karate cannot be, and are a recorded residual.

## 25. Runner memory

The runner never logs, prints or stores a value; `SecretBundle`, `EngineInput` and the collector's second-pass
copies are cleared when the execution ends. `EngineInput` validates everything before writing a byte, so no
failure path leaves a half-built frame.

## 26. Sandbox handoff

The C bootstrap reads exactly the source frame from stdin, freezes the source filesystem, drops every privilege
and `execve`s the adapter with an empty environment. Only then does the adapter read the engine frame
(`KAASENG1`, secrets, optional egress endpoint, `KAASEND1`), require EOF, close fd 0, and print
`kaas.secrets=CONSUMED`. Secrets therefore exist only after the boundary has closed.

## 27. FD lifecycle

Two attaches before start: a stdin-only frame attach that closes (giving EOF) and a separate output attach. The
adapter's `FileInputStream(FileDescriptor.in).close()` makes the JVM dup2 `/dev/null` over fd 0, so fd 0 cannot be
reused for the old pipe; measured in-sandbox as `engine_stdin_after_frame=-1` and no `0->pipe:` (K22-26).

## 28. Karate secret API

`kaas.secrets.<BINDING_KEY>` via `Runner.global("kaas", …)`, and `kaas.egress` under an allowlist. No environment
variable, system property or file carries a value (K22-15). The classpath `karate-config.js` configures Karate's
proxy with the execution's egress credential.

## 29. Docker logging change

Every sandbox that runs tenant code uses `LogConfig NONE` and `core` ulimit 0; platform probes keep `json-file`.
Measured while the sandbox is alive: `docker_log_driver=none`, `docker logs` holds nothing (K22-18).

## 30. Attach capture

The trusted `OutputCollector` is attached before the container starts and is the only capture. **Added after
review:** the engine container's environment no longer carries the egress credential (it never read it), so the
credential is not in container metadata (K22-38).

## 31. Redaction algorithm

Per-stream Aho-Corasick over raw bytes, holding back `longest − 1` bytes, merging overlapping matches into maximal
runs, replacing each with `[REDACTED]`. Registered sequences: every pinned value, the egress credential, and its
`Basic base64("kaas:"+token)` form (K22-39). **Added after review:** each sanitised line is redacted again, because
sanitising deletes characters and can rejoin a value the first pass saw apart (K22-37). Fuzzed against a reference
implementation for 3 000 rounds.

## 32. Chunk boundaries

Any chunking, down to one byte per Docker frame (K22-21: a holdback one byte short is killed).

## 33. Output / truncation

The ceiling counts redacted bytes, so a value crossing it is replaced before it is counted and cannot leave a
prefix (K22-22). Truncation is an infrastructure failure, never a verdict.

## 34. UTF-8

Incremental decoding after redaction, so redaction sees exact bytes and a code point split across frames
survives; malformed input becomes U+FFFD.

## 35. Result protocol

Read on a raw, trusted branch (`ProtocolScanner`) that keeps counts and closed-vocabulary words only — never a raw
line (K22-25). The runner requires `kaas.engine=karate 2.1.2` and `kaas.secrets=CONSUMED` exactly once each
(K22-28) and one result key; a duplicate is `MALFORMED`, a value outside the vocabulary is refused, and a secret
equal to a protocol word cannot change the verdict.

## 36. stdout

Redacted independently; the secret-bearing suite prints each value on stdout at least three times and requires the
raw match count to be positive **and** the kept transcript to be free of it.

## 37. stderr

Same, independently, with separate redactor state (K22-23).

## 38. Exceptions

A Karate/JVM exception whose message carries the value is printed through the same redacted streams; tested with
an HTTP failure whose body echoes the secret (E2E test 3). JVM crash posture: `-XX:-HeapDumpOnOutOfMemoryError
-XX:+ExitOnOutOfMemoryError -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/tmp/hs_err.log` (inside the sandbox's own
tmpfs), core ulimit 0.

## 39. Karate failure

A failing assertion is the tenant's `FAILED`; a wrong secret against the controlled target is a tenant `FAILED`
with `infrastructure_outcome=SUCCEEDED` (E2E test 2, the control for §48).

## 40. Proxy leakage

The proxy prints nothing derived from a request — headers, cookies, path, query, body (K22-29) — and dnsjava is
pinned to ERROR. It accepts the capability as `Proxy-Authorization: Basic` (as Karate sends it) and strips it
upstream. The E2E reads the real proxy's log during the run, with a control proving the watcher read it.

## 41. DB leakage

Every text, JSON and bytea column of every public table is scanned for the value after the run
(`secret_in_database=false`; K22-06).

## 42. Queue leakage

Outbox and dispatch payloads are scanned (`secret_in_queue=false`); the only outbox writer runs at queue time,
before any decrypt, and carries no secret material (review-confirmed).

## 43. Metadata leakage

`docker inspect` of the live sandbox: environment, command, labels — no value (K22-14, K22-16). No value in the
engine's `/proc/self/environ`, cmdline or system properties (K22-15).

## 44. Test-report leakage

Every generated value embeds a per-run CI nonce; after both suites the gate scans all three modules' build trees
for it. **Hardened after review:** grep's status is read explicitly, the control is planted inside a scanned tree
and must be among the hits, the step runs after failures too, and the failure-report step masks any token carrying
the nonce. Suite assertions on transcripts are booleans, so a failing assertion cannot print one.

## 45. Dump policy

No heap dump, no core, HotSpot error file only inside the sandbox's tmpfs, which dies with it; core ulimit 0 on
the container.

## 46. Authority races

Before redemption: refused without calling Vault. During the Vault call: decrypted, refused by the post-call
check, cleared, nothing sent (K22-27). After delivery: KAAS-16's continuous authority stops the sandbox.
**Residual:** a revocation committing between the post-call check and the response write is delivered, and a
running JVM keeps a value it already holds (§62).

## 47. Secret-free provider outage

With Vault sealed, a secret-bearing run starts no engine (`SECRET_PROVIDER_UNAVAILABLE`) and a secret-free run in
the same deployment passes (E2E test 90; K22-12, K22-13). Nothing contacts Vault at startup.

## 48. Real Vault E2E

`SecretExecutionPipelineTests` against real Vault 1.18.5 (dev-mode storage, TLS, AppRole, derived key — the
fixture's root token is never given to the application), real PostgreSQL, the real control plane, the real runner
loop, the real proxy and a controlled target that accepts only the SHA-256 of the exact generated value. Vault's
own operator-side decryption proves the stored ciphertext is Vault's encryption of the submitted value; a stand-in
provider fails that (K22-33b).

## 49. Real gVisor secret E2E

In CI both secret suites run under `runsc` with the real bootstrap, real freeze, real Karate 2.1.2 and the real
redactor; the evidence gate requires `runtime=runsc` as reported by the daemon (K22-34: this machine's runc
evidence is refused by the real gate).

**Locally** the E2E cannot pass the production runtime guard without runsc, so it was run with that one guard
temporarily relaxed (`if (false && …)` in `ExecutionLoop`), restored byte-for-byte after every run and never
committed. Local E2E results are corroboration only; the CI job is the evidence.

## 50. Rotation / revocation E2E

A run pinned to version 1 with version 2 written and version 1 revoked is refused with `SECRET_VERSION_REVOKED`,
launches no sandbox, and version 2 is never used (the target saw no authenticated request).

## 51. Docker-log proof

Measured while the secret-bearing sandbox is alive: log driver `none`, `docker logs` returns nothing, and no
metadata carries the value (`docker_log_driver=none`, `docker_persistent_secret=false`).

## 52. Security review

Four independent read-only reviewers covered the 13 required areas and answered the 12 required questions.

| Question | Answer |
| --- | --- |
| One project's ciphertext under another's context? | No (SQL scoping, composite FKs, derived-key tag; key derivation now verified) |
| Latest instead of pinned? | No |
| Revoked version silently upgrade? | No (new runs pin the highest non-revoked version — documented) |
| Runner requests an arbitrary secret? | No |
| Plaintext reaches RabbitMQ? | No |
| Plaintext reaches Docker json-file before redaction? | No — no log driver |
| Tenant reads leftover secret bytes from stdin? | No |
| Redactor misses a frame-split value? | No |
| Truncation exposes a prefix? | No |
| Malformed result logs the raw line? | No |
| Secret-free run fails because Vault is sealed? | No |
| Gate passes if the feature never received the secret? | No — target-side SHA-256 auth plus raw-match counts |

Findings and disposition:

| Finding | Severity | Disposition |
| --- | --- | --- |
| Transit key derivation not verified | P2 | **Fixed** (§8, K22-36) |
| Verdict forgery by tenant | reported P1 | **Measured.** The "unterminated stdout line" variant does not work under Karate 2.1.2 (its console summary ends the open line first); the adapter's leading newline is defence in depth (K22-41 equivalent). Forge-then-exit / `System.setOut` is the tenant-owned verdict, documented (ADR-034, secret-free doc, IMPLEMENTATION_STATUS). |
| Harness: no try/finally, no baseline | P1 | **Fixed** (§54) |
| Proxy Basic form could be logged by Karate | P1 | **Measured**: the engine has no SLF4J provider (NOP) so Karate logs no headers; the Basic form is now redacted anyway (K22-39) |
| Sanitising can rejoin a secret | P2 | **Fixed** (§31, K22-37) |
| Egress credential in container metadata | P2 | **Fixed** (§30, K22-38) |
| Plain-http secret transport | P2 | **Fixed** (§22, K22-42) |
| Revocation in the last instant still delivered | P2 | Residual, documented (§46, §62) |
| Encoded forms (Basic, JSON, URL) not redacted | P2 | Residual, documented; only the platform's own encoding is registered |
| Nonce scan hides I/O errors; control outside the scanned tree | P2 | **Fixed** (§44) |
| Evidence survives `clean` | P2 | **Fixed**: evidence directories are emptied before each suite |
| Pipeline evidence values hard-coded | P2 | **Fixed**: computed from observations |
| K22-33 models a missing provider, not a stand-in | P2 | **Added** K22-33b |
| New runs fall back when the newest version is revoked | P3 | Documented (ADR-034) |
| Pre-V13 secret snapshot rows not in migration fixture | P3 | **Fixed** (§16, K22-40) |
| Buffer growth / partial-frame copies | P3 | **Fixed** (exact sizing, validate-before-write) |
| Secret-path log backstop bypassable by context path | P3 | **Fixed** |
| Redemptions consumed by provider failures; refusal names the exact denial; login storm on policy 403 | P3 | Accepted, recorded |

## 53. Mutation evidence

Each mutant: exact single-match edit verified, diff recorded, fresh test results (results directory deleted,
`clean<Task>` forced), JUnit XML parsed for which test failed and why, every file restored byte-for-byte, and the
whole tree verified clean afterwards. A mutant killed only by a compile error would be INVALID; there were none.

| Mutant | Fault | Verdict at HEAD | Killed by |
| --- | --- | --- | --- |
| K22-01 | SecretVersion omitted from snapshot | KILLED | `ConfigurationHttpIntegrationTests` — runIntentCreatesOnlyASealedReproducibleSnapshotWithSafeTenantScopedReads() |
| K22-02 | execution resolves latest instead of pinned | KILLED | `SecretExecutionControlPlaneTests` — eachRunReceivesTheVersionItPinnedAndNeverTheLatest() [run A received version 1] |
| K22-03 | revoked version still decrypts | KILLED | `SecretExecutionControlPlaneTests` — aRevokedVersionFailsClosedAndNeverAdvancesToTheNextOne() |
| K22-04 | tenant context omitted from Transit | KILLED | `VaultTransitClientTest` — a ciphertext does not decrypt under another project's context, even in the same organizati |
| K22-05 | wrong project context accepted | KILLED | `VaultTransitClientTest` — a ciphertext does not decrypt under another project's context, even in the same organizati |
| K22-06 | plaintext persisted in DB | KILLED | `SecretExecutionControlPlaneTests` — initializationError |
| K22-07 | secret capability issued with empty scope | KILLED | `SecretExecutionControlPlaneTests` — eachRunReceivesTheVersionItPinnedAndNeverTheLatest() [redemption answered 409] |
| K22-08 | exact-set validation removed | KILLED | `SecretScopeExactnessTest` — anythingElseIsRefused() [an extra secret] |
| K22-09 | another worker or a stale epoch redeems | KILLED | `SecretExecutionControlPlaneTests` — noOtherAuthorityCanRedeemAndNothingIsDecryptedForIt() |
| K22-10 | extra secret accepted by the runner | KILLED | `SecretBundleTests` — an extra, a missing, or a different key refuses the whole bundle |
| K22-11 | partial set executes | KILLED | `SecretExecutionControlPlaneTests` — aVersionRevokedAfterAuthorizationIsRefusedAtRedemption() |
| K22-12 | provider outage executes the engine anyway | KILLED | `SecretExecutionPipelineTests` — with Vault sealed, a secret-bearing run starts no engine and a secret-free run still passe |
| K22-13 | secret-free run contacts Vault | KILLED | `SecretExecutionControlPlaneTests` — aRevokedVersionFailsClosedAndNeverAdvancesToTheNextOne() [revocation is decided without decrypting anything] |
| K22-14 | secret put in the container environment | KILLED | `SecretBearingKarateExecutionTests` — while the secret-bearing sandbox is alive, the daemon holds no log of it and no metadata c [secret_in_container_metadata] |
| K22-15 | secret put in a JVM property | KILLED | `SecretBearingKarateExecutionTests` — the secret is in no environment, command line, system property or open descriptor of the e |
| K22-16 | secret put in argv | KILLED | `SecretBearingKarateExecutionTests` — while the secret-bearing sandbox is alive, the daemon holds no log of it and no metadata c [secret_in_container_metadata] |
| K22-17 | secret written to a host file | KILLED | `SecretExecutionPipelineTests` — a runtime-generated secret travels from the tenant, through Vault, into Karate under gViso [secret_in_host_files] |
| K22-18 | secret-bearing sandbox json-file logging restored | KILLED | `SecretBearingKarateExecutionTests` — while the secret-bearing sandbox is alive, the daemon holds no log of it and no metadata c [docker_log_driver] |
| K22-19 | output attach removed | KILLED | `SecretBearingKarateExecutionTests` — a secret reaches Karate through kaas.secrets, is printed raw on both streams, and is redac [The engine reported no result.] |
| K22-20 | redactor removed | KILLED | `OutputCollectorTests` — stdout and stderr are redacted independently, each across its own frame boundaries |
| K22-21 | overlap state removed | KILLED | `OutputCollectorTests` — sanitising cannot reassemble a value the first pass saw in pieces |
| K22-22 | truncation moved before redaction | KILLED | `OutputCollectorTests` — redaction happens before the ceiling, so a value crossing it leaves no prefix behind |
| K22-23 | stderr bypasses the redactor | KILLED | `OutputCollectorTests` — stdout and stderr are redacted independently, each across its own frame boundaries |
| K22-24 | EOF matcher flush removed | KILLED | `OutputCollectorTests` — sanitising cannot reassemble a value the first pass saw in pieces |
| K22-25 | malformed protocol line kept raw | KILLED | `OutputCollectorTests` — a malformed protocol line carrying a secret is counted and never kept |
| K22-26 | engine stdin left open with the frame's pipe | KILLED | `SecretBearingKarateExecutionTests` — the secret is in no environment, command line, system property or open descriptor of the e [descriptor 0 no longer refers to the frame's pipe] |
| K22-27 | capability loss still sends plaintext | KILLED | `SecretExecutionControlPlaneTests` — authorityLostWhileTheProviderIsDecryptingDeliversNothing() |
| K22-28 | runner accepts a wrong engine identity | KILLED | `ExecutionLoopEngineTests` — the runner itself refuses a verdict from an engine that is not the adjudicated Karate [identity lines ] |
| K22-29 | proxy logs Authorization and cookies | KILLED | `EgressProxyProtocolTests` — nothing a request carries -- headers, cookies, path, query, body -- is written to the prox |
| K22-30 | cross-project SecretReference access accepted | KILLED | `SecretExecutionControlPlaneTests` — theWriteSurfaceRefusesEverythingItShould() |
| K22-31 | the redaction test's tenant never emits the secret | KILLED | `SecretBearingKarateExecutionTests` — a secret reaches Karate through kaas.secrets, is printed raw on both streams, and is redac [raw_stdout_secret_observed] |
| K22-32 | the controlled target accepts any secret | KILLED | `SecretExecutionPipelineTests` — the target refuses any other value: the same run with a different secret is a FAILED test [a wrong secret is the tenant's failing test] |
| K22-33 | gate without Vault | KILLED | `SecretExecutionPipelineTests` — a runtime-generated secret travels from the tenant, through Vault, into Karate under gViso [version write answered 503] |
| K22-17 | secret written to a host file (after the fail-closed scan) | KILLED (battery 2, after the scan fix) | `SecretExecutionPipelineTests` — a runtime-generated secret travels from the tenant, through Vault, into Karate under gViso [secret_in_host_files] |
| K22-33b | a stand-in provider replaces Vault | KILLED — by the intended mechanism: the operator-side Vault decryption of the stored ciphertext fails (the fixture throws on Vault's 400 rather than returning false, so the label differs) | `SecretExecutionControlPlaneTests` — aWrittenVersionIsStoredOnlyAsARealTransitCiphertext(CapturedOutput) |
| K22-36 | non-derived key accepted | KILLED | `VaultTransitClientTest` — a key that is not derived, and so would ignore the tenant context, is refused before any u |
| K22-37 | sanitising rejoins a secret (second pass removed) | KILLED | `OutputCollectorTests` — sanitising cannot reassemble a value the first pass saw in pieces |
| K22-38 | egress credential left in the engine container's environment | KILLED | `EngineInputTests` — an engine's container environment carries no egress credential; a probe's still does |
| K22-39 | proxy Basic form not redacted | KILLED | `EngineInputTests` — the credential is redacted raw and in the Basic form karate-config.js sends it in |
| K22-40 | V13 snapshot CHECK rejects pre-V13 rows | KILLED | `MigrationUpgradeTests` — everyMigrationAppliesToAPopulatedDatabaseFromThePreviousVersion() |
| K22-41 | adapter verdict can be swallowed by an open tenant line | SURVIVED — equivalent under Karate 2.1.2: its console summary ends the tenant's open line before the adapter prints (measured); the newline is defence in depth |  |
| K22-42 | secrets redeemed over plain http | KILLED | `SecretTransportTests` — a redemption over plain http to another host is refused before any request is made |

Battery 1 was first run against `bc164cc`: 32 of 33 killed, and **K22-17 survived** — the end-to-end host-file scan aborted a whole root on the first unreadable directory, so a secret written into the JVM temporary directory went unseen. The scan was rewritten to fail closed with a planted control per root (§44); the rerun of battery 1 against the review-fixed HEAD above kills all 33, K22-17 on `secret_in_host_files`.

**K22-34 / K22-35 (gate mutations)** ran the real CI scripts against this machine's real evidence:
`evidence-gate.py` refuses runc evidence (`runtime: ['runc'] (expected exactly runsc)`, plus the unapplied nonce),
refuses `docker_log_driver=json-file`, refuses a second contradicting `runtime` line and a missing key; the same
evidence with only the runtime corrected passes (control). `junit-gate.py` refuses a skipped test and a short count;
the real results pass. The rewritten nonce scan was exercised locally: clean tree passes, a planted file carrying
the nonce fails, and the control file is removed.

## 54. Harness evidence

Scripts: `mutate.py` (battery 1, run first against `bc164cc` and again against the review-fixed HEAD) and
`mutate2.py` (battery 2). After review, the harness refuses to start on a dirty tree, restores in `finally`
(including on a timeout), re-checks the whole tree after each mutant, parses JUnit XML with ElementTree, records
skipped counts, and runs **unmutated baselines** of every command first — all six passed (API 35 tests, runner
unit 35, secret suite 6, E2E 5, proxy 21, Karate forgery 1). Battery 2 also records an expected failure label per
mutant.

## 55. CI design

`secret-execution-gate` is the tenth mandatory job: no job-level `if`, no `continue-on-error`, no path filter. It
installs runsc, chooses a per-run nonce, runs `secretExecutionTest` and `secretExecutionPipelineTest` from clean,
requires `SecretBearingKarateExecutionTests=6` and `SecretExecutionPipelineTests=5` executed with nothing skipped,
checks every evidence key exactly (every occurrence must equal the expected value), scans build output for the
nonce, and runs the leak gate (no containers, networks or runsc processes left).

## 56. CI evidence

Run of record for the implementation: **run 36269755525 on `287c45e`**, all ten jobs `success`: backend,
hostile-execution-gate, synthetic-execution-pipeline, execution-egress-gate, strong-runtime-gate,
karate-execution-gate, **secret-execution-gate**, web, contracts, infrastructure. Its secret gate, under runsc:
suites executed 6 and 5, skipped 0; raw stdout and stderr secrets observed, `persisted_raw_secret=false`, engine
`karate 2.1.2` `PASSED`, `runtime=runsc`, `docker_log_driver=none`, `docker_persistent_secret=false`, no value in
container metadata, environment, cmdline or system properties, `engine_stdin_after_frame=-1`,
`secret_authenticated_request=true`, revoked version refused, provider outage refused, secret-free run with the
provider down `PASSED`, `sentinel_in_reports=false`, `containers=0 networks=0 runsc_processes=0`.

**Final run: 36274892142 on `5ecf07a`**, which includes everything after `287c45e` — `bc164cc` (docs,
`SecretScopeExactnessTest`), `bc44606` (review fixes), `3a7b3b5` (docs) and `5ecf07a` (this report). All ten jobs
`success`. Its secret gate, under runsc: `SecretBearingKarateExecutionTests` executed 6 and
`SecretExecutionPipelineTests` executed 5, skipped 0, failures 0; `secret_provider=vault-transit`,
`secret_provider_auth=VALID`, `secret_encryption=VALID`, `secret_version_pinned=true`, `secret_capability=VALID`,
`secret_redemption=VALID`, `secret_authenticated_request=true`, raw stdout and stderr secrets observed,
`persisted_raw_secret=false`, `redaction=VALID`, `docker_log_driver=none`, `docker_persistent_secret=false`, no
value in container metadata, environment, cmdline, system properties, database, queue, host files or proxy logs,
`engine_stdin_after_frame=-1`, engine `karate 2.1.2` `PASSED`, `runtime=runsc`, `revoked_version_refused=true`,
`provider_outage_refused_secret_run=true`, `secret_free_run_with_provider_down=PASSED`,
`sentinel_nonce_applied=true`, `sentinel_in_reports=false`, `containers=0 networks=0 runsc_processes=0`.
Values in the final run's evidence are computed from observations (§52), and the host-file scan is the
fail-closed version (§44).

Local results before that push: API 365, runner 264, engine 11, proxy 118 — all green, nothing skipped; secret
suite 6/6 and E2E 5/5 as battery baselines (runc, guard relaxed for the E2E; corroboration only).

## 57. Existing gate hardening

`karate-execution-gate` now requires the whole suite (`KarateExecutionTests=11`, `KaasKarateAdapterTest=7`,
`EngineFrameTest=4`), reads the engine identity, verdict, runtime and secret channel from evidence the suite
writes (`runtime=runsc`, `secret_channel=CONSUMED`), and runs the leak gate. `junit-gate.py` keys results by file
name (a suite's display name is not its class).

## 58. Documentation reconciliation

New: ADR-034, `docs/security/tenant-secret-execution.md`, `docs/security/secret-redaction-boundary.md`,
`docs/architecture/secret-delivery-path.md`, `packages/api-contracts/secret-bundle.md`, `engine-frame.md`.
Reconciled every document that still said Karate was secret-free-only or that secrets were refused: README,
IMPLEMENTATION_STATUS, ADR index, the Karate execution model and path, the readiness matrix, the secret-free
security document and the KAAS-21 slice report sentence. The verdict-forgery claims in IMPLEMENTATION_STATUS and
the secret-free document were corrected to what was measured.

## 59. Files changed

`d1ad2ff..HEAD`: 122 files (52 added, 67 modified, 2 deleted, 1 renamed) before this report. Principal areas:
`apps/api` secrets package, execution capability and controller, V13, test fixtures (`VaultTransitFixture`);
`services/runner` redactor, collector, protocol scanner, engine input, launcher, loop, bundle parser, client;
`services/karate-engine` adapter, config, JVM flags; `services/egress-proxy` Basic credential; `tests/pipeline`;
`.github` workflow and three gate scripts; contracts and OpenAPI; documentation.

## 60. Known deferred deployment work

Vault backup, audit device and seal alerting; the staging execution host with runsc; WireGuard; the KaaS stack;
TLS between runner and control plane (§22); log-shipper exclusion of sandbox containers; monitoring and backups;
**a production runner daemon** — the execution loop is still composed only by tests; a staging rehearsal.

## 61. Ops prerequisites

The Transit key exactly as in §4; an AppRole policy granting `update` on `transit/encrypt/kaas-tenant-secrets` and
`transit/decrypt/kaas-tenant-secrets` and `read` on `transit/keys/kaas-tenant-secrets`; the five variables on the
control plane only; the CA file; runsc on execution hosts; https (or loopback) from runner to control plane.

## 62. Accepted residuals

- `source_mount_nodev=false` and `java_threads_bounded=false`, as accepted in ADR-032/033.
- Plaintext in managed-runtime memory for one execution; JDK/HTTP/Karate copies cannot be cleared.
- Transformed exfiltration and exfiltration to an authorized destination; encoded forms are not redacted
  (only the platform's own proxy-credential encoding is).
- The verdict is tenant-controlled (forge-then-exit, `System.setOut`).
- A revocation in the last instant of a redemption is delivered; revocation does not reach a running JVM.
- The egress credential is readable by tenant code (scoped to one execution, revalidated per request).
- Observed once locally: an `aRequestNamingTwoDestinationsIsRefused` proxy test returned status 0 and passed on
  three reruns; watched in CI, not reproduced there.

## 63. Final verdict

**SECRET-BEARING KARATE EXECUTION COMPLETE**

| | |
| --- | --- |
| Final SHA | `5ecf07a2c39d10385108b4503bc6a80febb751e3` |
| CI run | 36274892142 |
| Jobs | 10 of 10 `success`: backend, hostile-execution-gate, synthetic-execution-pipeline, execution-egress-gate, strong-runtime-gate, karate-execution-gate, secret-execution-gate, web, contracts, infrastructure |
| Secret gate | 6 + 5 tests executed, 0 skipped; every evidence key as required, under `runtime=runsc` (§56) |

Earlier drafts of this section recorded **SECRET EXECUTION INCOMPLETE**. The review fixes had only been
verified locally, and the push was not permitted in the implementing session. That was superseded once the
final SHA was pushed and passed CI.

This verdict is about application capability. It is **not** deployment readiness: the production runner daemon
and all deployment work remain deferred (§60), and Ops must grant the AppRole `read` on
`transit/keys/kaas-tenant-secrets` (§61). GitHub branch-protection state was not read, so nothing here claims
which checks are required.

## 64. Recommended next slice

A production runner daemon composing the execution loop with real configuration, TLS to the control plane and the
continuous-authority lifecycle — the piece that turns the tested loop into something a host runs. Deployment
itself (Vault, runsc host, WireGuard) follows the Ops plan, not an application slice.
