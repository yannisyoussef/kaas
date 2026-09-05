# Signed sandbox security attestation

**Status: IMPLEMENTED and VALIDATED.** `kaas.sandbox-security-attestation.v4`, signed with Ed25519 and
verified against a deployment-pinned public key. v2 is refused and its model is deleted.

## The shape of it

```
                    Host runtime  (a runner host, or a CI runner)
                          │
        ┌─────────────────┴─────────────────┐
        │                                   │
  Hostile gate                        Egress gate
  17 mandatory observations           5 egress observations
  (scoped to the runtime, ADR-028)
                                      + the proxy image it used
        └─────────────────┬─────────────────┘
                          │  structured results, never text
                          ▼
              Attestation Producer                    services/runner
                          │
                          │  ◄──  Ed25519 PRIVATE key
                          │       a file on this host; never travels,
                          │       never generated, never logged
                          ▼
                signed attestation
                          │
                       transport                       operator configuration
                          │                            (no endpoint accepts one)
                          ▼
                  Control Plane                        apps/api
                          │
                          │  ◄──  pinned PUBLIC key, selected by keyId
                          │       never from the document, a URL, a tenant,
                          │       a worker, or a remote JWKS
                          ▼
              authenticated evidence
                          │
                          ▼
              ExecutionAuthorization
                          │
                          ▼
                ExecutionCommand
             assessmentDigest bound
                          │
                          ▼
                       Runner
             refuses a command naming
             evidence that is not its own
```

Explicitly:

- **A tenant cannot produce one.** There is no endpoint that accepts an attestation, and an architecture test
  fails the build if anything outside the execution package can even reach the type.
- **A worker cannot register a key.** There is no registration API. Rotation is an operator action on
  configuration.
- **The control plane cannot sign.** It holds verification authority only. An architecture test fails the
  build if its execution package acquires a private-key type, a key generator, or a PKCS#8 spec.
- **The private key never travels.** It is read from a file on the assessed host and used there.
- **A CI subject is not a production subject.** The same producer builds both; a control plane accepts only
  subjects an operator configured, so one cannot stand in for the other.

## Two implementations, one written rule

```
packages/api-contracts/sandbox-security-attestation-signing.md      NORMATIVE
        │                                   │
        ▼                                   ▼
services/runner                       apps/api
AttestationPayload                    AttestationPayloadFields
  (producer's preimage)                 (verifier's preimage)
        │                                   │
        └──────────► identical bytes ◄──────┘
                          │
                          ▼
        packages/api-contracts/fixtures/sandbox-security-attestation-signing/
          1765-byte preimage, its digest, its signature, 19 negatives
```

They agree by both implementing the contract, never by sharing code — the control plane's build fails if it
depends on the module that holds container-runtime access, so a shared signing library is forbidden by the
boundary that lets the runner touch a daemon at all.

The preimage was implemented a **third** time, in Python, from the contract alone. All three produce
byte-identical output, and the committed vectors are fixed to it. That is why a vector failure is a contract
failure rather than a test failure: regenerating the vectors to make them pass is a schema change and
invalidates every attestation in existence.

## What is inside the signature, and what is beside it

| Inside the preimage | Outside |
|---|---|
| schema version, attestation id, producer version | `payloadDigest` — derived from the preimage |
| **keyId**, **signatureAlgorithm** | `signature` — over the preimage |
| profile version, runtime, runtime subject, runtime generation | |
| probe image digest, proxy image digest (or `ABSENT`) | |
| assessed instant | |
| every mandatory control and verdict, ordered, counted | |
| every egress control and verdict, ordered, counted | |

`keyId` and `signatureAlgorithm` are inside not because swapping them is exploitable — verification fails
either way — but so the signer *commits* to which key and algorithm it used, which removes the substitution
question by construction rather than by argument.

`payloadDigest` is outside because a payload cannot contain its own digest without circularity, and the
signature is outside because a payload cannot contain its own signature at all.

## The order the verifier works in

```
strict parse ─► schema is v4 ─► exact properties ─► algorithm is ED25519
      │
      ▼
resolve EXACTLY ONE key by keyId ──► unknown? refuse. never try the others.
      │
      ▼
reconstruct the preimage from the parsed fields
      │
      ├──► recompute the digest, compare in constant time
      └──► verify the signature over those reconstructed bytes
      │
      ▼   ═══════ nothing above this line was trusted ═══════
      │
runtime subject ─► freshness ─► profile ─► exact control coverage ─► every control PASS
```

The signature is verified over the **reconstructed** preimage, never over the bytes as received. Verifying
received bytes would authenticate a serialization: reformatting the artifact would break it, and a field the
verifier does not understand would be signed over without ever being read. Reconstruction means the signature
covers exactly the fields this build knows, and the parser refuses a document carrying anything else.

Everything above the line reports a **category only**. The document is not yet authenticated, so it is
attacker-influenceable input, and a diagnostic quoting its contents would repeat attacker-chosen text into a
log. Below the line the contents are a trusted producer's statement, and an operator can safely be told which
control failed.

## Why a type, not a convention

`VerifiedSandboxSecurityAttestation` has a package-private constructor and the verifier is the only thing that
calls it. Every method on it reads security verdicts, and "has anyone checked where these came from?" is
answered by the type system rather than by reading the call chain. A parsed-but-unauthenticated document
cannot be represented as one, so it cannot reach the semantic checks by accident.

## Two decisions, made by two parties

| Question | Answered by | Using |
|---|---|---|
| Is this evidence authentic? | control plane | the pinned public key |
| Is it recent, for this runtime, under this profile, all passing? | control plane | its own configuration and clock |
| Does this command's evidence describe **me**? | runner | its own attestation artifact |

The runner does not verify the signature. It is the party that *produces* attestations and holds the signing
key; a signature checked against its own key would prove nothing about anything. What it does is compare the
command's `assessmentDigest` against the digest it **recomputes** from its own artifact — reading the
artifact's own `payloadDigest` property would let a locally edited digest redefine what this runtime considers
its evidence.

That check can only ever move the answer towards refusal. There is no way for a runner to make a command
acceptable, which is the property that stops this from becoming a self-assertion channel.

## Where the clocks are

| Question | Answered by |
|---|---|
| When was this assessed? | the producer's clock, stamped into the signed payload |
| Is it stale now? | the control plane's clock, at every authorization |
| Are those the same clock? | **no**, and a one-minute future-skew tolerance says so |

The tolerance is a correction, not slack. With zero tolerance, ordinary sub-second drift between two hosts
makes a freshly produced attestation unusable and refuses all execution with a message that would lead nobody
to the clock.

## What it does not do

It does not strengthen the sandbox. The controls are the same controls, the container is the same container,
and the kernel is still shared. What changed is that the statement about them is now one a pinned key made,
rather than one somebody typed. ADR-022's stronger-runtime prerequisite for tenant code is untouched.
