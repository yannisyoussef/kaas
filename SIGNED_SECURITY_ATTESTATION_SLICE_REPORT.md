# Signed security attestation slice report

## 1. Executive summary

The security evidence this platform refuses to execute without is now **signed by the thing that observed it**
and **verified against a key the deployment pinned**, rather than being a document an operator typed with a
SHA-256 digest over their own typing.

The old chain proved exactly one thing — that the document matched its own digest. It did not prove the
document came from a security gate, that any runtime observed the controls it listed, or that the deployment
authorizing an execution was consuming evidence from the runtime that was assessed. A human read a gate's
output, wrote `"NON_ROOT_UID": "PASS"` into a file, and recomputed a digest over what they had written.

Now: the gates return structured observations, a producer on the assessed host maps them and signs a canonical
payload with an Ed25519 key, and the control plane strict-parses, resolves exactly one pinned key by `keyId`,
reconstructs the payload, recomputes the digest, verifies the signature — and only then reads a verdict.
`ExecutionCommand` binds the authenticated payload digest, and the runner independently refuses a command
naming evidence that does not describe its own runtime.

**Unsigned v2 is refused outright.** No migration window, no `allow-unsigned` flag, and the v2 model is
deleted from the source tree rather than left dormant.

Verification: **apps/api 314, runner 139, execution egress 35, pipeline 33, web 1 — 0 failures, 0 errors,
0 skips.** 25 security mutations run, 24 killed and 1 recorded as jointly covered rather than claimed.
**Three real defects fixed**, each found by a test or a run rather than by review.

**This does not strengthen the sandbox.** A perfectly signed attestation describing a shared-kernel container
is a trustworthy statement about a boundary ADR-022 still does not approve for hostile tenant code.

## 2. Starting commit

`a0ec64f` — "docs: record the execution egress GitHub Actions result". Confirmed clean and in sync with
`origin/codex/project-feature-control-plane` before any work began, and the kaas-13 evidence the prompt cites
was re-read from the actual run rather than taken on trust: run 33965580342 on `70e1ea1`, seven of seven
green, egress `executed=35 skipped=0 containers=0 networks=0`, hostile `116/0`, pipeline `33/0`.

## 3. The existing unsigned weakness

```
gate runs and prints results
      ↓  a human reads them
      ↓  a human writes "NON_ROOT_UID": "PASS"
      ↓  a human recomputes the digest over what they wrote
control plane: the digest matches what they wrote  ✓
```

Every step after the first is a place to record something never observed. The digest authenticated the typing.

Worse, it degraded with every control added. ADR-026 had just introduced five more, and its own residual risks
named this as the largest remaining hole in the evidence chain, with the observation that it "matters more
than it did" — because each new control is another field an operator can get wrong or invent.

## 4. Threat model

| Threat | Control |
|---|---|
| Operator or attacker writes a passing document | Signature by a key only the assessed host holds |
| Document edited after signing | Payload reconstructed and digest recomputed; signature over reconstructed bytes |
| Attacker supplies their own verification key | Keys come from deployment configuration only — never the document, a URL it names, tenant config, a worker, or a JWKS |
| Attacker relabels which key signed a document | `keyId` is inside the signed preimage; the verifier resolves exactly one key and never tries others |
| Algorithm confusion | `signatureAlgorithm` compared to one literal, never dispatched on; curve pinned explicitly |
| Evidence from host A authorizing host B | Runtime subject inside the signature and validated against a configured set |
| An old assessment authorizing a changed host | `assessedAt` plus a bounded maximum age; signatures do not confer freshness |
| A command routed to the wrong runner | Runner independently compares the command's `assessmentDigest` to its own evidence |
| Control plane forging evidence it then verifies | It holds no private key; asserted structurally by an architecture test |
| Tenant or worker submitting an attestation | No endpoint accepts one; asserted structurally |
| Diagnostics repeating attacker-chosen text | Pre-authentication refusals report a category and nothing from the document |

## 5. Cryptographic algorithm choice

**Ed25519**, and only Ed25519. Measured on this JDK rather than assumed:

```
public  X.509 SubjectPublicKeyInfo  44 bytes
private PKCS#8                      48 bytes
signature                           64 bytes
deterministic                       verified — same payload and key give identical bytes
KeyFactory("Ed25519")               refuses an Ed448 key (InvalidKeySpecException)
KeyFactory("EdDSA")                 ACCEPTS an Ed448 key
getAlgorithm()                      returns "EdDSA", not "Ed25519", for both curves
```

Chosen over RSA (padding-mode selection) and ECDSA (per-signature nonce). Determinism is what lets the
repository fix a signature vector rather than merely round-trip one.

The last two measurements shaped the code: an algorithm-name check is **not** a curve check, so the curve is
pinned through `EdECPublicKey.getParams().getName()`.

`signatureAlgorithm` must be the literal `ED25519`. The verifier compares that string and does not dispatch on
it. Algorithm agility here would be algorithm confusion with extra steps.

## 6. Key trust model

Asymmetric, never HMAC. The control plane needs **verification** authority and must never be able to mint an
attestation — it would then be both the party making the claim and the party checking it.

| Party | Holds |
|---|---|
| Assessed runtime | the private signing key, in a file, never travelling |
| Control plane | the pinned public keys, from its own configuration |
| Operator | the artifact, and both configurations |
| Tenant, worker | nothing |

## 7. Private key handling

Read from a **file path**. Not an environment variable — visible in `/proc/<pid>/environ` and inherited by
children. Not a process argument — visible in `ps` to every user on the host, and to build scans if it were a
Gradle property.

Never generated. An automatically generated signer destroys pinning continuity: the control plane would not
have the new key, every attestation would be refused, and the obvious repair for that is to make the control
plane trust whatever turned up. Missing, unreadable, malformed, or wrong-curve is a failure with a category.

Never written to a command, a database, a broker, a container label, a metric, a log, or the attestation.
`AttestationSigner.toString()` is overridden to redact, because a default `toString` on a key holder is how
private material reaches a log line about something else.

The published test keys in `packages/api-contracts/fixtures/` include their private halves **deliberately**:
the vectors are worthless without them, and a key anyone can read can never be mistaken for a production
signer. There is no default trust root that would admit them.

## 8. Public key pinning

`kaas.execution.attestation-trusted-keys` as `keyId=base64Spki` entries. Standard Base64 with padding, 44-byte
SPKI, parsed with `KeyFactory("Ed25519")`, curve checked explicitly. Duplicate key ids are refused — a
configuration whose meaning depends on parse order fails in the direction where a removed key keeps working.

**A `keyId` is not authority.** It selects one pinned key. Unknown ids refuse, and the verifier never tries the
others: "some key we trust signed something" is a far weaker statement than "the key this document names
signed this document". A vector exists for exactly this — a document claiming key 1 carrying key 2's signature
over the same payload — and both the trust-store and verifier tests pin it.

Absent or malformed key configuration makes the **execution security subsystem** unavailable without
preventing the application from starting. Read-only product endpoints have nothing to do with attestation, and
an outage of everything is the consequence that gets a security control switched off rather than fixed.

## 9. Key rotation

Several keys may be pinned at once: add the next, switch the producer, remove the previous. No window exists
in which no valid attestation is accepted. There is no registration API — rotation is an operator action on
configuration, never something a worker does to itself.

## 10. Attestation v3 schema

`kaas.sandbox-security-attestation.v3`. Fourteen signed fields plus two envelope fields:

| Signed | Envelope |
|---|---|
| `schemaVersion`, `attestationId`, `producerVersion` | `payloadDigest` |
| `keyId`, `signatureAlgorithm` | `signature` |
| `securityProfileVersion`, `runtime`, `runtimeSubject`, `runtimeGeneration` | |
| `probeImageDigest`, `egressProxyImageDigest` (or absent) | |
| `assessedAt`, `mandatoryControls`, `egressControls` | |

`keyId` and `signatureAlgorithm` are **inside** the preimage. Not because swapping them is exploitable —
verification fails either way — but so the signer commits to which key and algorithm it used, removing the
substitution question by construction rather than by argument.

`payloadDigest` is outside: a payload cannot contain its own digest without circularity. `signature` is
outside: a payload cannot contain its own signature at all.

Unknown property: refuse. Missing property: refuse. `FAIL_ON_UNKNOWN_PROPERTIES` applies only to bean binding
and does nothing for tree parsing, so this is an explicit allowlist — the same lesson the v2 source recorded.

## 11. Canonical signed payload

Signing a JSON serialization was rejected outright: Jackson's field ordering, whitespace, and serializer
configuration must never define security semantics. A library upgrade would change what a signature means, and
the symptom would be a verifier rejecting genuine evidence, followed by somebody relaxing the verifier.

`packages/api-contracts/sandbox-security-attestation-signing.md` is normative. Domain separator first,
four-byte big-endian length prefixes on **every** string, labelled fields, counts before their entries,
controls ordered by name, `assessedAt` as an ISO-8601 UTC instant at whole seconds, and `ABSENT` as a value
distinct from the empty string.

Length prefixing is not decoration. Without it, `("AB", "C")` and `("A", "BC")` produce identical bytes and
the choice of where one field ends belongs to whoever supplied them.

## 12. Domain separation

The preimage begins with `KAAS_SANDBOX_SECURITY_ATTESTATION_V3`, itself length-prefixed. The same signing key
therefore cannot accidentally authenticate a different KaaS document type that happens to have the same byte
shape, and a future v4 preimage can never be mistaken for a v3 one even if every other field matched.

## 13. Payload digest

```
payloadDigest = "sha256:" + hex(SHA-256(canonical preimage))
signature     = Ed25519(canonical preimage)
```

Both over the same bytes. The verifier recomputes the preimage from the parsed fields and checks both
independently, in that order — the digest first because it is cheap and catches every field tamper, the
signature second because it is the only thing that catches a genuine signature over an untouched payload from
the wrong key.

It never verifies a signature over a digest the document supplied. That would authenticate the digest field
rather than the evidence, and a document could then carry a genuine signature over a digest describing
something else entirely. A mutation for exactly this (`s04`) turns 18 tests red.

## 14. Mandatory control derivation

`HostileExecutionAssessment` → the producer reads `SecurityCheck.control()` and `verdict()` off it. There is no
`produce(Map<String, String>)` and no parameter that sets a verdict.

`UNSUPPORTED` is carried as itself rather than folded into `FAIL`. "This host cannot enforce it" and "this host
was shown not to enforce it" are different facts, neither is a pass, and an operator needs to tell them apart.

**Only `Enforcement.MANDATORY` checks reach the signed claim** — see §33, defect 1.

## 15. Egress control derivation

`EgressEnforcementAssessment` → the same mapping. Present only when egress was actually assessed; a deployment
that runs no allowlist executions should not have to stand up a proxy to produce an attestation, and an
artifact making an egress claim it never measured would be the exact failure this slice exists to end.

Absent egress evidence produces no egress controls **and no proxy image**, and the control plane reads that as
"makes no egress claim" and refuses an `ALLOWLIST`. A `DENY_ALL` execution is unaffected, which is the
separation ADR-026 established.

## 16. Probe image identity

Carried as a `sha256:` digest and refused if it is a tag. A mutable tag is not an identity, and the whole
mandatory assessment describes what that image did inside the sandbox.

## 17. Proxy image identity

New in v3. `EGRESS_PROXY_IMAGE_PINNED=PASS` says an image was pinned; it does not say **which**, and evidence
that cannot name the artifact it demonstrated is evidence about nothing in particular.

The gate's return type changed to carry the image **with** its verdicts (`EgressEnforcementAssessment`),
replacing a getter over a field the gate populated as a side effect of running. That removes a real hazard: a
producer could otherwise pair one assessment's verdicts with another assessment's image, which is the
substitution a signature is meant to prevent and which was possible before the signature was ever computed.

## 18. Runtime subject

An **opaque operator-assigned label**, configured on both sides. Not a hostname, MAC address, machine serial,
cloud instance id, or host path — the artifact travels, and each of those names the machine to whoever reads
it.

The control plane holds the set of subjects it accepts and **validates** against it. A control plane that
merely displayed the subject would let one signature authorize every runtime that key ever signed for. An
empty accepted set refuses everything, by construction rather than by a branch.

## 19. Runtime-generation limitations

`runtimeGeneration` is derived from the container runtime's own opaque instance identity, hashed with domain
separation so nothing host-identifying is published:

```
"gen:" + first 32 hex of SHA-256( VALUE("KAAS_RUNTIME_GENERATION_V1") || VALUE(instance id) )
```

**What it gives:** attestations taken against different runtime instances are distinguishable, and the value
is inside the signature so it cannot be moved onto evidence from elsewhere.

**What it does not give: reboot invalidation.** A daemon's instance identity ordinarily survives a host
restart, so an attestation remains verifiable across one. This is stated rather than papered over, and the
maximum age was deliberately **not** widened to compensate. Freshness is bounded by `assessedAt` and the
configured maximum, and by nothing else.

The daemon's `Name` — the hostname — is read and discarded. So are kernel version, operating system, and
server version: all host-descriptive, all exactly what an attacker would like from an artifact that travels.

## 20. Producer

`SandboxSecurityAttestationProducer` takes assessment objects that only the gates construct. That does not
make fabrication impossible — a process with the signing key can sign anything — but it moves the boundary
from "can type" to "holds the private key", which is the whole of the improvement.

It **signs failures truthfully**. A gate that observed a failure produces an attestation recording it. Refusing
to serialize would leave an operator with no artifact and no explanation; authorization is a separate question
answered elsewhere, and a signed failure is authentic evidence that authorizes nothing.

`:services:runner:produceSandboxSecurityAttestation` runs both gates for real, resolves both image digests,
captures runtime identity, signs, and writes **atomically** — temporary file in the same directory, `0600`
where POSIX permissions exist, then `ATOMIC_MOVE`. A half-written security artifact fails closed, but "fails
closed" is not "is fine": an operator finding a partial file has to work out whether the producer crashed or
somebody edited it, and an atomic replace means that question never arises.

## 21. Transport model

Deployment configuration, unchanged from v2 and deliberately so. **No endpoint accepts an attestation**, and
none registers a verification key. An architecture test fails the build if anything outside the execution
package can reach the attestation type, the source, the verifier, **or the trust store** — the last two matter
most, because a surface reaching them could let a caller nominate who is trusted to sign, which is strictly
worse than being able to submit a document.

No database persistence was added. The attestation is runtime evidence, not a record, and a table would invite
a surface that writes to it.

## 22. Control-plane verifier

Independent implementation. Order:

1. strict parse → 2. schema is v3 → 3. exact property set → 4. algorithm is `ED25519` → 5. resolve **one** key
by `keyId` → 6. reconstruct the preimage → 7. recompute and compare the digest → 8. verify the signature →
**then** 9. runtime subject → 10. freshness → 11. profile → 12. exact control coverage and every control
`PASS`.

The signature is verified over the **reconstructed** preimage, never the received bytes. Verifying received
bytes would authenticate a serialization: reformatting would break it, and a field the verifier does not
understand would be signed over without being read.

`VerifiedSandboxSecurityAttestation` has a package-private constructor and only the verifier calls it. Every
method on it reads verdicts, and "has anyone checked where these came from?" is answered by the type rather
than by reading the call chain.

Steps 1–8 report a **category only**. The document is not yet authenticated, so it is attacker-influenceable
input and a diagnostic quoting it would repeat attacker-chosen text into a log. After step 8 the contents are
a trusted producer's statement and an operator can be told which control failed.

## 23. Strict parsing

Explicit property allowlist, because `FAIL_ON_UNKNOWN_PROPERTIES` does not apply to tree parsing — the lesson
already recorded in this repository, restated here because the failure mode is identical. Unknown property,
missing property, non-textual value, wrong signature length, non-canonical Base64: each refuses.

Standard Base64 only. MIME decoding would accept line breaks and stray characters, which means several
spellings of one signature — two documents that are the same to one reader and different to another.

**Schema before properties.** The schema *defines* the property set, so checking a document's properties
against v3's list before knowing it claims to be v3 checks it against the wrong list — see §33, defect 2.

## 24. Freshness and clock semantics

`assessedAt` is stamped by the producer's clock; the comparison instant comes from the control plane. Two clock
domains, and the code says so. A one-minute future-skew tolerance is a **correction**, not slack: with zero
tolerance, ordinary sub-second drift makes a freshly produced attestation unusable and refuses all execution
with a message that would lead nobody to the clock.

The maximum age is unchanged from ADR-023 and was deliberately not widened because producing signed
attestations is more operationally involved than typing one. That incentive is exactly what the previous
report warned about.

`PersistableClock` is untouched; the backend suite proving it still passes.

## 25. v2 compatibility decision

**Refused.** No migration window, no `allow-unsigned-attestation`, no `skip-signature-verification`, no
`development-trust-all`. The v2 record is deleted from the source tree, so there is no dormant path to
re-enable and no flag whose default somebody could change.

The platform serves no tenant execution, which makes this the right moment for the breaking change and the
wrong moment to build a fallback that would outlive its reason.

## 26. ExecutionAuthorization integration

Unchanged in shape. `attestations.attestation()` now returns a type only the verifier can construct, so an
unsigned document, one signed by an unpinned key, or one edited after signing cannot be represented — the
refusal happened at startup and nothing downstream can read a verdict from it.

Authenticity is not sufficiency: the service then asks `reasonItCannotAuthorize(now, maxAge, profile,
acceptedSubjects)` on every authorization, because freshness, subject, profile and coverage are properties of
*this authorization, now*.

## 27. ExecutionCommand binding

`SandboxSecurityProfileReference(profileVersion, assessmentDigest)` — **unchanged**. What changed is that the
digest it binds is now the authenticated payload digest rather than a self-consistency check over hand-written
text. No new field, no migration, and the existing runner-side digest verification keeps working.

## 28. Runner-side compatibility

`RuntimeAttestationBinding` reads the artifact this runtime was deployed with and **recomputes** the payload
digest from its fields — reading the artifact's own `payloadDigest` property would let a locally edited digest
redefine what this runtime considers its evidence. `CommandValidator` refuses a command naming anything else.

The runner does **not** verify the signature. It is the party that produces attestations and holds the signing
key; a signature checked against its own key proves nothing. The check can only move the answer towards
refusal, which is what stops it becoming a self-assertion channel.

What it defeats: a command routed to another runner, or issued before this runtime was re-assessed. Neither is
something control-plane verification can notice.

## 29. CI versus deployment evidence

Same producer, same contract, **not interchangeable**. A CI attestation carries
`runtimeSubject=kaas.runtime.github-actions` and says the mechanism works on a GitHub-hosted runner. A
deployment attestation describes the host that will execute something.

A control plane accepts only subjects an operator configured, so a CI artifact cannot authorize anything
anywhere — not because somebody remembered to prevent it, but because the subject would not match.

## 30. CI gate integration

`hostile-execution-gate` was extended rather than an eighth job added. Signing the assessment is the same
claim the gate already makes, made transportable; a new job would fragment one property across two required
checks and double the container work.

Two new steps: produce a signed attestation from what the gate just observed, then verify it with the control
plane's **own** verifier and prove tampering breaks it. The job asserts `verification=VALID`,
`authorizes=true`, `mandatoryControls=16`, `controlsNotPassing=[]`, and that a document with one flipped
verdict **fails**.

That last step is not decoration: without it a verifier stubbed to return `VALID` would pass every other
check. Both directions were run locally before committing — the step exits 0 as written and exits 1 when the
tampering is removed from it.

**No GitHub Actions artifact upload.** Evaluated and declined: it would add an action dependency for provenance
the job already proves inline, and the artifact describes a CI runner that must never be consumed as
production evidence. The existing action SHA-pinning debt is untouched rather than expanded into this slice.

## 31. Gradle input tracking

The signing contract and the whole vector directory are declared inputs of both test tasks, because they *are*
the agreement between producer and verifier and a change to one changes what a signature means.

Mutation-checked, not asserted:

```
1st run (after an input change): > Task :apps:api:test
2nd run (nothing changed):       > Task :apps:api:test UP-TO-DATE
3rd run (one vector edited):     > Task :apps:api:test
```

The producer task also declares the resolved proxy image context, which is what makes Gradle build it first —
without it the path is queried before the producing task completes, and the task fails outright.

## 32. Security review

Ten adversarial passes, each asked the prompt's own questions.

| # | Lens | Question | Answer |
|---|---|---|---|
| 1 | Applied cryptography | What can be modified without invalidating the signature? | Only `payloadDigest` and `signature`, and both are recomputed or verified. Every other field is length-prefixed inside the preimage; 11 field-family mutations each turn tests red |
| 2 | Canonicalization / parser ambiguity | Which two documents could be "the same"? | None found: length prefixes, counts before entries, ordered controls, one timestamp representation, `ABSENT` distinct from empty, standard Base64 only, exact property set |
| 3 | Runtime attestation / subject binding | Can evidence from host A authorize host B? | No. The subject is inside the signature and validated against a configured set; an empty set refuses everything |
| 4 | Key management / rotation | Can the document choose its own trust root? | No. Keys come only from deployment configuration, `keyId` selects exactly one, and the verifier never iterates |
| 5 | Authorization / command binding | Can a stale command bind to different evidence? | No. The command binds the authenticated digest, and the runner refuses evidence that is not its own |
| 6 | Hostile-execution gate integration | Does the producer sign observations or a caller's verdict? | Observations. There is no `produce(Map)`; **this lens found defect 1** |
| 7 | Egress evidence integration | Can verdicts and image identity be paired from different runs? | Not any more — the gate now returns them together |
| 8 | QE / mutation validity | Which test stays green when signature verification is removed? | None: `s01`/`s02` each turn 3 red. **This lens found defect 3 and the joint-coverage finding** |
| 9 | CI / supply chain | Can the gate pass without producing or verifying? | No; and the tamper step fails when its own tampering is removed |
| 10 | Architecture / trust boundaries | Can the control plane sign what it verifies? | No — asserted structurally, and the rule is mutation-checked |

**Findings, all fixed:**

1. **P0 — the producer signed 18 mandatory controls where exactly 16 are required.** §33.
2. **P1 — the property allowlist ran before the schema check.** §33.
3. **P1 — every negative vector was refused for the wrong reason.** §33.
4. **P2 — curve enforcement is jointly covered, not independently proven.** Recorded in code and in ADR-027
   rather than resolved by weakening the first guard to make the second observable, which would be optimising
   for the mutation report rather than for the boundary.

## 33. Mutation evidence

**25 mutations run. 24 killed; 1 recorded as jointly covered.**

| # | Mutation | Result |
|---|---|---|
| s01 | signature verification always returns true | RED (3) |
| s02 | signature verification skipped | RED (3) |
| s03 | payload digest never compared | RED (2) |
| s04 | signature verified over the claimed digest instead of the payload | RED (18) |
| s05–s11 | mandatory controls / egress controls / probe digest / proxy digest / runtime subject / assessedAt / profile version each removed from the preimage | RED (21 each) |
| s12 | unknown `keyId` accepted and verification proceeds | RED (3) |
| s13 | verifier tries every trusted key regardless of `keyId` | RED (2) |
| s14 | any algorithm identifier accepted | RED (1) |
| s15 | any schema version accepted, so unsigned v2 returns | RED (2) |
| s16 | staleness no longer refused | RED (2) |
| s17 | future assessment beyond skew accepted | RED (1) |
| s18 | evidence for any runtime subject accepted | RED (3) |
| s19 | exact control coverage becomes containment | RED (1) |
| s20 | producer folds deployment-specific observations back into the mandatory claim | RED (1) |
| s21 | producer signs an assessment that observed nothing | RED (1) |
| s22 | producer accepts a mutable tag as probe image identity | RED (1) |
| s23 | signer stops checking the curve | **SURVIVED — jointly covered.** See below |
| s24 | runner stops refusing a command bound to foreign evidence | RED (1) |
| s25 | runtime binding reads the document's own digest instead of recomputing | RED (2) |

**s23, recorded honestly rather than engineered away.** Measured: `KeyFactory("Ed25519")` refuses an Ed448
PKCS#8 outright, so the explicit curve check cannot fire and removing it kills nothing. It is kept because the
factory's strictness is a property of the JCA provider while the check is a property of this code — and the
one change that would make the mutation kill, using the generic `EdDSA` factory, means **weakening the first
guard to get a greener report**. The same reasoning applies to the trust store, where a 44-byte SPKI length
check fires ahead of both. Both comments now say so.

**Three real defects, each found by something running rather than by reading:**

1. **The producer signed 18 mandatory controls.** The hostile gate reports `DEPLOYMENT_SPECIFIC` checks —
   `USER_NAMESPACE`, unsupported on this host — alongside the mandatory ones, and the producer folded them in.
   The control plane requires exactly the contracted 16 and matches for **equality**, so every attestation this
   producer made was refused `CONTROL_FAILED` while naming a control nothing has ever required. Found by
   running the real chain end to end on this host; no unit test existed that could have caught it, and `s20`
   now exists so none is needed again. Filtering is not hiding evidence: the gate's assessment is the complete
   record, and this map is the authenticated claim about the controls a verifier checks.
2. **The property allowlist ran before the schema check**, so a v2 document reported `MALFORMED`. The schema
   defines the property set, so validating properties against v3's list before knowing the document claims v3
   checks it against the wrong list — and sends an operator with an old artifact hunting for a typo. Reordered;
   the normative contract was updated to match, since it is the contract that is wrong if the code is right.
3. **Every negative vector was refused for the wrong reason.** The generator wrote a `$why` explanation into
   each document; that is an unknown property, so all 19 refused as `MALFORMED` and each one's actual reason
   went untested. The `is not VALID` parameterized test was green throughout — the by-stage categorisation test
   is what found it. Explanations moved to a sidecar index.

## 34. QE evidence

| Suite | Tests | Failures | Errors | Skips |
|---|---|---|---|---|
| `apps:api` | 314 | 0 | 0 | 0 |
| `services:runner` | 139 | 0 | 0 | 0 |
| `services:runner:egressSecurityTest` | 35 | 0 | 0 | 0 |
| `services:egress-proxy` | 116 | 0 | 0 | 0 |
| `tests:pipeline` | 33 | 0 | 0 | 0 |
| `apps:web` | 1 | 0 | 0 | 0 |

New: `AttestationSigningVectorTest` on both sides, `VerifiedAttestationSemanticsTest`,
`SandboxSecurityAttestationProducerTest`, `RuntimeAttestationBindingTest`, and a rewritten
`ExecutionSecurityGateDependencyTests` covering unpinned keys, unsigned v2, malformed trust roots, signed
failures, foreign subjects, and stale evidence.

Anti-vacuity throughout: the "signed by an unpinned key" case pins **both** test keys so it is refused for key
selection rather than as an unknown key; the runner binding test asserts the same command is **accepted** when
the runtime holds the evidence it names; and the new architecture rule was mutation-checked by adding a
`PrivateKey` field and watching it go red.

## 35. Cryptographic vectors

`packages/api-contracts/fixtures/sandbox-security-attestation-signing/` — a canonical payload, its 1765-byte
preimage as hex, its digest, its signature, two published test keypairs, one valid signed document, and 19
negatives each mutating exactly one field family.

**The preimage was implemented a third time, in Python, from the contract document alone.** All three
implementations produce byte-identical output, and the fixture is fixed to that value — so neither Java side is
checked against something it computed. A vector failure is a contract failure, and regenerating the vectors to
make it pass is a schema change that invalidates every attestation in existence.

## 36. Logging and metrics

Logged: `attestationId`, `keyId`, `payloadDigest`, and the verification outcome category. Never: the private
key, public key material, the raw attestation, the signature, control contents from an unverified document, or
host-sensitive runtime diagnostics. Malformed operator input is never echoed.

`kaas.security.attestation.verification{result}` and `kaas.security.attestation.authorization{result}`, both
dimensioned by the `AttestationVerification` enum and nothing else. Never `attestationId`, `keyId`,
`runtimeSubject`, digest, host or IP — those are what would let a metrics store answer "which runtime is
failing verification", which is a question about a host.

## 37. Files changed

**New — control plane:** `AttestationPayloadFields`, `AttestationVerification`, `PinnedVerificationKeys`,
`VerifiedSandboxSecurityAttestation`, `SandboxSecurityAttestationVerifier`, `RequiredSecurityControls`,
`AttestationTrustStore`, `AttestationVerificationCli`, plus `AttestationSigningVectorTest`,
`VerifiedAttestationSemanticsTest`, `SignedAttestationFixture`, `AttestationSigningVectors`.

**Deleted:** `SandboxSecurityAttestation` (v2) and `SandboxSecurityAttestationTest`.

**New — runner:** `AttestationPayload`, `AttestationSigner`, `SignedAttestation`, `RuntimeIdentity`,
`SandboxSecurityAttestationProducer`, `SandboxSecurityAttestationCli`, `RuntimeAttestationBinding`,
`AttestationFailure`, `AttestationProductionFailed`, `EgressEnforcementAssessment`, plus three test classes.

**Modified:** `SandboxSecurityAttestationSource` (verifies rather than parses), `ExecutionAuthorizationService`,
`ControlPlaneArchitectureTest`, `ExecutionSecurityGateDependencyTests`, `ExecutionAuthorizationTests`,
`ExecutionAuthorizationGateRefusalTests`, `MandatoryControlContractTest`, `EgressEnforcementGate`,
`EgressCapability`, `CommandValidator`, both pipeline suites, both build files, `.github/workflows/ci.yml`.

**Contracts:** new signing contract and 24 fixture files; `mandatory-sandbox-controls.json` moved to v3.

**Documentation:** ADR-027, `docs/adr/README.md`, `docs/security/signed-runtime-attestation.md`,
`docs/architecture/signed-sandbox-security-attestation.md`, `docs/architecture/security-boundaries.md`,
`docs/architecture/observability-contract.md`, `README.md`, `IMPLEMENTATION_STATUS.md`, and this report.

## 38. Verification

The full existing matrix plus the new tasks, all run locally:

```
./gradlew clean check --no-daemon -x :services:runner:test -x :services:runner:egressSecurityTest -x :tests:pipeline:test
./gradlew :services:runner:cleanTest :services:runner:test --no-daemon
./gradlew cleanEgressSecurityTest :services:runner:egressSecurityTest --no-daemon
./gradlew :tests:pipeline:cleanTest :tests:pipeline:test --no-daemon
./gradlew :services:runner:produceSandboxSecurityAttestation ... && ./gradlew :apps:api:verifySandboxSecurityAttestation ...
npm --prefix packages/api-contracts ci && run validate:schemas && run lint:openapi
npm --prefix apps/web ci && lint && typecheck && test && build && audit --omit=dev
docker compose -f infrastructure/local/docker-compose.yml config
git diff --check
docker ps -aq --filter label=kaas.managed=true          # empty
docker network ls -q --filter label=kaas.managed=true   # empty
```

The producer/verifier round trip on this host, against real gates:

```
verification=VALID       mandatoryControls=16    egressControls=5
controlsNotPassing=[]    authorizes=true         egressEnforceable=true
probeImageDigest=sha256:2139f33f…                egressProxyImageDigest=sha256:72ef60b7…
one verdict flipped  →   DIGEST_MISMATCH
```

## 39. GitHub Actions verification

*To be recorded once pushed.* This slice modifies `.github/workflows/ci.yml`, and the `gh` credential in this
environment still lacks the `workflow` scope — the same operational issue kaas-13 hit. The fix is one operator
command, `gh auth refresh -h github.com -s workflow`; history must not be rewritten, the workflow change must
not be dropped, and the gate must not be bypassed to get around it.

What will be recorded: final commit SHA, workflow run ID, all seven job conclusions, and the executed and
skipped counts read from **each gate's own evidence-inspection step** rather than from a Gradle summary.

## 40. Required-check governance

Unchanged at seven: `backend`, `hostile-execution-gate`, `synthetic-execution-pipeline`,
`execution-egress-gate`, `web`, `contracts`, `infrastructure`. No job was added — the attestation evidence
belongs to `hostile-execution-gate`, which already owns the claim it authenticates.

This report does not claim branch protection requires them. That is administration state this work cannot
read; what is asserted is that the jobs exist, nothing in the workflow can make them skip, and they are named
exactly as above.

## 41. Residual risks

1. **Replay inside the freshness window.** A valid attestation stays usable until it ages out. Signatures
   provide authenticity, not recency.
2. **No reboot invalidation.** `runtimeGeneration` distinguishes runtime instances; a daemon identity
   ordinarily survives a restart.
3. **The signing key lives on the assessed host.** Whoever can read it can attest anything about that runtime —
   the same party who controls the daemon. Signing moves the boundary from "can type" to "holds the key"; it
   does not make a compromised host honest.
4. **Curve enforcement is jointly covered**, not independently proven. §33.
5. **The producer is trusted to report what the gate returned.** Nothing cryptographically binds the gate's
   output to the producer's input inside one process.
6. **Docker remains a shared-kernel boundary.** Unchanged, and not addressed by anything here.

## 42. Remaining blockers before tenant Karate

1. **The ADR-022 runtime prerequisite** — a stronger boundary than shared-kernel Docker, with the gate re-run
   against it. **A signed attestation does not satisfy this and must not be read as progress towards it.**
2. **A worker that heartbeats during execution**, for runs longer than a lease.
3. **A production `SecretValueProvider`.**
4. **`FeatureRevision` source redemption into a sandbox**, architecturally prevented today.
5. **A tenant-facing egress policy surface**, so an allowlist is authored rather than requested.

## 43. Recommended next slice

**Worker heartbeating during execution, and the lease model that follows from it.** It is now the oldest
unaddressed deferral — created by ADR-024, restated by ADR-026, and untouched here — and it is the one that
blocks any run longer than a single lease period, which the 30-minute execution budget already exceeds. Every
subsequent slice that lengthens an execution makes it worse, and unlike the attestation work it cannot be done
incrementally alongside something else: it changes what a lease means.
