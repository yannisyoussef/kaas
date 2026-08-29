# Execution Authorization and Capability Slice Report

## 1. Executive summary

Claiming a run established who owns an infrastructure attempt. This slice establishes whether that owner may
execute it, as a separate decision — and then deliberately stops, because an `ExecutionCommand` now exists and
there is nothing that executes one.

The separation is the whole point. The conditions that make execution safe are not the conditions that make
ownership valid, and they can stop being true while ownership continues: a worker legitimately holds an attempt
at a moment when the run was cancelled, when the sandbox on its host cannot be shown to confine anything, when
the run needs egress no launcher can enforce, or when it binds secrets the platform cannot supply.

Secret-free runs can reach a command. Secret-bearing runs stop at authorization, because no production secret
provider exists and issuing a command promising secrets nothing can deliver would move the failure past the
point where a sandbox had already started.

## 2. Security objective

Make execution authority **assignment-scoped, short-lived, and revalidated**, so that fencing is effective
rather than merely recorded — and prove that an unexpired token is worthless the moment its basis moves.

## 3. Current trust boundary

```
RabbitMQ → consumer → inbox → QUEUED→CLAIMED → attemptId + epoch + lease
                                    ↓
                       ExecutionAuthorization  (this slice)
                                    ↓
                    SourceCapability / ExecutionCommand
                                    ↓
                                  STOP
```

No `CLAIMED → PROVISIONING`. No broker publication. No sandbox invocation. No Karate.

## 4. ExecutionAuthorization

One immutable decision per `(attempt, assignment epoch)`, recording what was established at issuance: the sealed
snapshot digest, the sandbox profile version and assessment digest, the probe image digest, and the network
policy revision. Those exist so an audit can answer *on what basis was this allowed*, and so a later check can
notice the basis has moved.

## 5. Authorization prerequisites

Run is `CLAIMED`; attempt is current; assignment held by this worker at this epoch; lease live; snapshot sealed,
non-empty and within bundle limits; sandbox attestation present and trustworthy; network policy enforceable;
secret requirements satisfiable. Every step fails closed, ordered so the cheapest and most specific refusals come
first — a stale assignment learns nothing about the deployment's security posture.

## 6. Worker identity binding

The authenticated service principal from the internal filter chain, never a request field. It is a JWT with a
subject in the reserved `kaas.` namespace and no tenancy — deployment-configured service identity, **not mTLS**,
and this report says so rather than implying a stronger boundary than the deployment has. Execution authorization
is exactly as strong as the heartbeat, which was the requirement.

## 7. Assignment epoch binding

Identity and epoch are always checked together. An epoch alone lets any worker act as the current owner; an
identity alone lets a replaced worker act under an assignment it has lost. Epoch replay is structural in both
directions: a bumped epoch on the same attempt fails the assignment check, and a new attempt fails on attempt
identity.

## 8. Lease binding

```
capability.expiresAt ≤ authorization.expiresAt ≤ attempt.lease.expiresAt
```

True by construction, and **re-established on every request rather than frozen**. Freezing it was a liveness
dead end found in review: a lease is thirty seconds a healthy worker renews indefinitely, so an authorization
anchored once died half a minute after issuance while its worker was fine, and the uniqueness constraint made a
replacement impossible. Re-anchoring moves the window forward against the current lease and cannot widen
authority.

## 9. Hostile security gate dependency

The gate lives in `services/runner`, which holds container-runtime access and which the control plane is
build-guarded against depending on. Its verdict crosses as a document.

Not a flag: a passing attestation enumerates every mandatory control with its verdict, checked against the set
the control plane independently requires with **exact equality in both directions**. A truncated document cannot
pass by omitting the control it failed; an assessment for a weaker control set cannot be accepted. No endpoint
accepts one, asserted by an architecture rule rather than by inspection. Absent evidence is
`SECURITY_GATE_UNAVAILABLE`, which is the production default.

**Honest limitations.** Deployment-scoped while the property attested is host-scoped; `runtime` recorded but not
compared; unsigned, so it detects a partially edited document rather than authenticating one; and no producer
exists yet, so an operator hand-authors it. A signed attestation with a pinned key is the named next step.

## 10. NetworkPolicyRevision

Platform-owned, immutable, digest-verified on load. `DENY_ALL` is enforceable and is the only revision that
exists. `ALLOWLIST` is modelled and **refused** as `NETWORK_POLICY_NOT_ENFORCEABLE` rather than degraded, and
the contract pins `const: DENY_ALL` on a command so the schema refuses it too. A worker never names a policy.

## 11. Source capability

Short-lived, assignment-scoped, worker-bound. 256 bits of `SecureRandom` behind a `kaas_src_` prefix; only a
SHA-256 is stored. Rotated on every delivery with the previous revoked in the same transaction, so at most one
live capability of each type exists per authorization — enforced by a partial unique index, not only by lock
discipline.

## 12. Source bundle

Built server-side from the snapshot's pinned `FeatureRevision` rows; no user archive is ever extracted, which
removes zip-slip by construction. Paths are re-validated at the bundle boundary even though they were validated
at revision creation, and now also rejected for case-folding collisions, directory-prefix collisions, and
non-UTF-8 encodability — the first two were demonstrated in review to silently lose two of four sources on
extraction. Total size is bounded at authorization rather than at archive time, so an oversized snapshot is
refused before a token exists.

## 13. Secret capability

Modelled and never issued. Scope is an enumeration rather than an expression, tenant ownership is structural
through composite foreign keys, and a hard invariant fails loudly if a secret-bearing run ever produces a command
with no secret capabilities — because the day a real provider reports available, the refusal stops firing and
nothing else would notice secrets being silently dropped.

## 14. Production secret-provider limitation

There is none. `UnavailableSecretValueProvider` refuses, and no property, profile, or auto-configuration can
substitute a permissive one: `SecretValueProvider` is a KaaS-owned interface, so substitution requires a class
implementing it — a code change with a review. Secret-bearing runs are refused at authorization.

## 15. Capability token security

Plaintext exists once, in the issuing response. Stored as bare-hex SHA-256, unique across both capability types
so a source token cannot also be a valid secret token. Plain SHA-256 rather than a password hash because the
token carries 256 bits of entropy — there is nothing to guess, and a slow hash would only slow legitimate
redemptions.

## 16. Capability expiry

Server-controlled, never client-chosen, bounded above by the authorization and thus by the lease. Both TTLs are
validated at startup against the database's own 30-minute ceiling, and `attestation-max-age` — the one knob that
could silently disable the only security gate — is bounded to 1 hour…7 days, because it has no database backstop.

## 17. Fencing and revocation

**Every redemption revalidates authoritative state** under the same lock every ownership writer takes. Expiry
bounds a leaked token; revalidation is what makes fencing effective. Authorizations are withdrawn when the next
request discovers the run is no longer claimed; a background reconciler that does so unasked is a named residual
risk.

## 18. ExecutionCommand

Immutable, digested, and bound to its authorization by composite foreign key — attempt, epoch, tenant, and run
must be the authorization's, because those are the fields a consumer would fence on and they were previously
free. Window bounded to 30 minutes. Undeletable, un-updatable, and un-truncatable.

## 19. Command canonical digest

A semantic canonicalization covering **every field the document emits**, length-prefixed, collections sorted and
counted. The rule it encodes — *a field the digest cannot cover must not be emitted* — is why the source
capability identifier was removed from the document rather than added to the digest.

**It is not a signature.** Unkeyed, so it establishes semantic identity and integrity against partial
modification and confers no authenticity. The contract says so on the field itself.

## 20. Persistence

Five tables: `network_policy_revisions`, `execution_authorizations`, `execution_capabilities`,
`execution_capability_secret_references`, `execution_commands`. Every parent-child relationship is composite and
tenant-carrying, so a child cannot disagree with its parent about who owns it.

## 21. Internal API

`POST /internal/v1/runs/{runId}/attempts/{attemptId}/execution-authorizations` and
`POST /internal/v1/source-bundles`. Deliberately absent from the public OpenAPI document. A caller supplies one
field — the assignment epoch it believes it holds — and it is checked rather than trusted. There is no field for
an organization, a worker identity, a security verdict, a network policy, an engine version, an image, a command,
or a sandbox flag. The capability token travels in its own header, because `Authorization` already carries the
worker's service credential and redemption revalidates both.

## 22. Clock authority

One domain: PostgreSQL `clock_timestamp()`, read **after** the lock is taken. Reading it before was a real defect
— a redemption waiting on a contended run row evaluated its windows against a pre-wait instant and served a
bundle 550 ms after the capability had expired. The attestation's future-dating check now allows one minute of
skew, because `assessedAt` is stamped on a different host than `now` and zero tolerance made a freshly produced
attestation unusable.

## 23. Race semantics

Authorization vs cancellation, vs lease expiry, vs heartbeat near expiry, two concurrent authorizations, and
redemption vs fencing. All serialize on the run row lock, taken in the order every other writer takes it. Six
concurrent racers produce exactly one authorization and one command.

**Correction:** the uniqueness constraint is never reached under that lock — review measured `issued=1,
reissued=5, denied=0`. The lock is what enforces single-authorization; the constraint is the backstop, and this
report does not credit it with what the lock does.

## 24. Security review

Eight independent adversarial reviews, each in an isolated git worktree: capability security, distributed
systems and fencing, PostgreSQL data integrity, source-bundle security, secret-management boundary, command
contract and network policy, Quality Engineering, and platform architecture.

Two P0s and roughly twenty P1s, several demonstrated against real PostgreSQL or by constructed collision. The
most serious were: cross-tenant secret scope accepted by a single-column foreign key whose own comment claimed
it was structural; commands able to contradict and outlive the authorization they hang off; a liveness dead end
in the TTL chain; a digest that omitted every authority-bearing field it emitted; a clock read on the wrong side
of a lock; and a fail-open that would drop every secret silently the day a provider arrived. All are fixed, with
regression tests inverting each reviewer's probe.

## 25. Mutation evidence

Two axes, per the standing rule: remove the **control**, and remove the **evidence**. Twenty-one control-disabled
mutations proven and eleven evidence-removed cases proven, across the authorization decision, the redemption
path, the capability window, the token shape, the digest, the schema guards, and the shared control contract.

**Three check-pairs are recorded as jointly covered rather than individually**, because no test can isolate them
and claiming otherwise would be false: the explicit lease-expiry check with the empty-window guard; the
redemption lifecycle check with the assignment check; and the reissue worker check with the authorize-path
assignment check. In each case removing either alone leaves the suite green and removing both turns it red. All
three are kept, with comments saying which pair they belong to and why they are not redundant in principle.

**The battery found more test weaknesses than production defects**, which is the point of running it:

- Two controls the service had no test for at all — an absent attestation and an untrustworthy one — because the
  suite always ran with a valid one configured.
- Two tests passing for a different reason than their name claimed: the unenforceable-policy test was refused by
  the digest check rather than the enforceability check, and the strict-parsing test failed on a missing field
  before the property allowlist was consulted.
- The whole `RUN_SNAPSHOT_INVALID` gate unexercised, and the snapshot size bound with it.
- Every guard on the reissue path uncovered, including *revocation is terminal* — which matters more since
  re-anchoring actively widens a window, so a missing check there would grant fresh authority rather than merely
  return a stale row.
- The redemption ceiling and the token charset check, each caught only coincidentally by an unrelated test.
- Two of my own new tests asserting the right property for the wrong reason: a capability marked spent was also
  marked revoked, so revocation refused it and the ceiling was never the cause.

**Withdrawn results, recorded rather than quietly replaced.** One mutation ("token stored in plaintext") first
made a checked exception unreachable, so the module stopped compiling and the harness read no failing tests as a
survival; redone so it compiles, seventeen tests go red. One harness run reported the shared-contract mutation as
surviving because the build failed without emitting a test count; verified manually, both modules go red.

## 26. QE evidence

267 tests in `apps/api`, zero failures, **zero skipped**; the hostile-execution gate suite green separately.
There is no `assumeTrue`, `@Disabled`, `@EnabledIf`, or try/catch around setup anywhere in either tree, so an
absent dependency fails loudly rather than passing vacuously.

Coverage spans authorization refusals for every prerequisite, idempotency and capability rotation, redemption
and fencing, schema-level impossibilities, the digest rule, bundle path safety and determinism, the capability
window, and the attestation verifier.

Two structural gaps the QE review found and this slice fixed are worth naming because neither was a test at all:

- **Both `MandatoryControlContractTest` classes were skipped by Gradle in exactly the case they exist for.** They
  read the shared contract file at runtime, and neither module declared it as a test input — so editing that
  file left the task `UP-TO-DATE`. CI restores the Gradle cache, so a pull request touching only the contract
  reproduced it. The assertions were sound; nothing ran them. Both modules now declare the input, verified by
  removing a control and watching each go red.
- **The four execution architecture rules sat outside the anti-vacuity guard**, so misspelling a package name
  left the whole class green including the guard itself. The guard now asserts the execution and internal
  selectors match real classes.

## 27. Migration evidence

V9 creates five tables and transforms no existing row: no `ALTER`, no `UPDATE`, no `DELETE`, no constraint
validated against pre-existing data. The migration gate therefore asserts what is actually true — that the
upgrade invents no authorization, capability, or command, and seeds exactly one policy revision with the digest
its own content implies. Seeding fixture rows to "cover" V9 would be seeding rows nothing evaluates, which is the
populated-but-unexercised fixture the standing rule exists to prevent.

**Correction:** V9 is **not** online, and an earlier comment said it was. `CREATE TABLE … REFERENCES` takes a
ShareRowExclusiveLock on each referenced table, blocking writes to `organizations`, `execution_attempts`, and
`secret_references` for the duration — measured by a second session being cancelled. It is a short maintenance
window, and the file now says so.

## 28. Observability

Low-cardinality counters for authorization outcomes, denials by category, capability issuance and redemption, and
command creation. Every tag is a bounded enum; no run, attempt, worker, organization, or capability identifier
appears as a label. Logs carry those identifiers and never a token, a secret, source content, or an
`Authorization` header.

## 29. Files changed

New: `V9__execution_authorization_and_capabilities.sql`, the `execution` domain/application/infrastructure
packages, `ExecutionAuthorizationController`, `execution-command.schema.json`,
`sandbox-security-attestation.schema.json`, `mandatory-sandbox-controls.json` and their fixtures, ADR-023,
`docs/architecture/execution-authorization-capability-slice.md`, this report, and five test classes.
Changed: `ApplicationConfiguration`, `application.properties`, `ControlPlaneArchitectureTest`,
`MigrationUpgradeTests`, `ConfigurationHttpIntegrationTests`, `validate-schemas.mjs`,
`docs/security/hostile-execution-boundary.md`, `docs/architecture/security-boundaries.md`, `docs/adr/README.md`,
`packages/api-contracts/README.md`, `README.md`, `IMPLEMENTATION_STATUS.md`.

## 30. Verification

Full results in §33. Summary: everything green, with no skips.

## 31. Residual risks

The attestation is deployment-scoped, unsigned, and has no producer. The command digest is unkeyed. Nothing
reconciles stale authority in the background. Authorizations, capabilities, and commands are undeletable and a
command duplicates the run's non-secret configuration, with no purge path. The source archive is built in heap
inside the redemption transaction. `reissue` would strip secret capabilities if any existed. The three logical-path
validators — bundle, control plane, and JSON schema — do not agree at the edges, and all three accept Windows
reserved device names.

## 32. Requirements before command execution

A producer and a signature for the attestation; source delivery into a sandbox; secret redemption against a real
provider; constrained egress so policies other than deny-all become enforceable; `CLAIMED → PROVISIONING` and the
delivery protocol it implies; and ADR-022's stronger kernel boundary, which this slice does not revisit and does
not satisfy.

## 33. Verification results

| Gate | Result |
|---|---|
| `./gradlew clean check -x :services:runner:test` | BUILD SUCCESSFUL, 3m01s |
| `./gradlew :services:runner:cleanTest :services:runner:check` (the required CI gate job) | BUILD SUCCESSFUL, 1m19s |
| Contract schema validation, all seven schemas | pass |
| OpenAPI lint | pass |
| Frontend lint / typecheck / test / production build / production audit | pass |
| Compose configuration | pass |
| `git diff --check` | clean |
| Leftover `kaas.managed` containers | 0 |

Migration verification is part of the backend suite: `MigrationUpgradeTests` applies every migration to an empty
database, and applies V9 to a database populated from V8 with representative rows, asserting that the upgrade
invents no authorization, capability, or command and seeds exactly one policy revision carrying the digest its
own content implies.

The suites use real PostgreSQL and RabbitMQ through Testcontainers, and the hostile-execution gate uses a real
Docker daemon. Nothing is stubbed and nothing skips: there is no `assumeTrue`, `@Disabled`, or `@EnabledIf`
anywhere in either test tree, so an absent dependency fails loudly rather than passing vacuously.

## 34. Recommended next slice

Constrained egress. It is the smallest change that makes ordinary Karate runs possible at all, it exercises the
`enforceable` split this slice built without needing user content in a sandbox, and it can be proven by the same
probe-and-gate machinery ADR-022 already established.
