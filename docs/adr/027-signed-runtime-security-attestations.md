# ADR-027: Signed runtime security attestations

**Status: ACCEPTED.** Supersedes the unsigned attestation format ADR-023 introduced and ADR-026 extended.
Schema `kaas.sandbox-security-attestation.v3` is required; v2 is refused.

## Context

The control plane refuses to authorize any execution unless a sandbox security attestation says this
deployment's sandbox enforces the controls the platform requires. Until now that attestation was a JSON
document carrying a SHA-256 digest over its own canonical content, and the control plane recomputed the digest
before trusting the verdicts.

That proves exactly one thing: **the document has not changed relative to its own digest.** It does not prove
the document came from a security gate, that any runtime ever observed the controls it lists, or that the
deployment authorizing an execution is consuming evidence from the runtime that was assessed. The workflow was:

```
gate runs and prints results
        ↓
a human reads them
        ↓
a human writes "NON_ROOT_UID": "PASS" into a file
        ↓
a human recomputes the digest over what they wrote
        ↓
the control plane checks that the digest matches what they wrote
```

Every step after the first is a place to record something that was never observed, and the digest
authenticates the typing rather than the observation. ADR-026's own residual risks named this as the largest
remaining weakness in the evidence chain, and said it mattered more with each control added — because each new
control is another field an operator can get wrong or invent.

## Decision

The attestation is **signed by the party that ran the gates**, and the control plane **verifies against a
pinned public key** before reading any verdict.

```
REAL RUNTIME
     ↓
HostileExecutionSecurityGate  ──┐
EgressEnforcementGate         ──┤ structured observations, not text
     ↓                          │
SandboxSecurityAttestationProducer
     ↓  canonical payload  ──  Ed25519 private key (producer side only)
SIGNED ATTESTATION
     ↓  transported unchanged, as deployment configuration
CONTROL PLANE
     ↓  strict parse → schema → pinned key by keyId → reconstruct → digest → signature
     ↓  THEN runtime subject, freshness, profile, exact control coverage
ExecutionAuthorization → ExecutionCommand binds the authenticated payload digest
```

An operator still transports the artifact. An operator no longer authors its security claims.

### What the signature authenticates

Not "a digest inside a document". The signed statement is:

> The KaaS attestation producer holding key `keyId` observed **exactly these control results**, for runtime
> subject `runtimeSubject` at generation `runtimeGeneration`, under profile `securityProfileVersion`, using
> probe image `probeImageDigest` and — where egress evidence applies — proxy image `egressProxyImageDigest`,
> on runtime `runtime`, at `assessedAt`.

Every element of that sentence is inside the signed preimage. A field outside it is a field an editor can
change while the document keeps verifying.

### Ed25519

Chosen over RSA and ECDSA: native JCA support in Java 25, 44-byte public keys and 64-byte signatures, no
padding mode to select, no per-signature nonce to get wrong, and deterministic output — which is what lets the
repository fix signature test vectors rather than merely round-tripping.

`signatureAlgorithm` **must** be the literal `ED25519`. The verifier compares that string and does not
dispatch on it. A document does not get to choose how it is verified; algorithm agility here would be
algorithm confusion with extra steps.

A JCA Ed25519 key reports `getAlgorithm()` as `EdDSA`, and so does an Ed448 key. An algorithm-name check is
therefore not a curve check, and the curve is pinned explicitly.

### Asymmetric, never a shared secret

No HMAC between runner and control plane. The control plane needs **verification** authority and must never
hold the authority to mint an attestation — if it could, it would be both the party making the claim and the
party checking it, and the signature would authenticate nothing anybody did not already control. An
architecture test asserts the control plane's execution package holds no private-key type at all.

### Pinned trust root, and `keyId` is not authority

Verification keys come from deployment configuration: `keyId → X.509 SubjectPublicKeyInfo`. Never from the
attestation, from a URL it names, from tenant configuration, from a worker request, or from a remote JWKS. Any
of those would let the document choose who is trusted to sign it, which is not a weaker version of this design
but the absence of one.

`keyId` **selects** one pinned key. An unknown id refuses. The verifier never tries the other trusted keys to
see whether one happens to work: "some key we trust signed something" is a far weaker statement than "the key
this document names signed this document", and a key an operator removed would otherwise keep working for any
document that stopped naming it. A vector exists for exactly this — a document claiming key 1 while carrying
key 2's signature over the same payload — and it must be refused.

### Rotation

Several keys may be pinned at once, so an operator adds the next key, switches the producer, and removes the
previous one with no window in which no valid attestation exists. There is no registration API: rotation is an
operator action on configuration, not something a worker can do to itself.

### Canonical signed payload

Signing a JSON serialization was rejected outright. Jackson's field ordering, whitespace, and serializer
configuration must never define security semantics — a library upgrade would change what a signature means,
and the symptom would be a verifier rejecting genuine evidence followed by somebody relaxing the verifier.

`packages/api-contracts/sandbox-security-attestation-signing.md` is normative and specifies the preimage:
a domain separator first, four-byte big-endian length prefixes on every string, labelled fields, counts before
their entries, controls ordered by name, `assessedAt` as an ISO-8601 UTC instant at whole seconds, and
`ABSENT` as a value distinct from the empty string.

Length prefixing is not decoration. Without it `("AB", "C")` and `("A", "BC")` produce identical bytes and the
choice of where one field ends belongs to whoever supplied them.

The domain separator means this signing key cannot accidentally authenticate a different KaaS document type
that happens to have the same byte shape, and a future v4 preimage can never be mistaken for a v3 one.

### Two implementations, one written rule

The producer lives in `services/runner`, which holds container-runtime access; the control plane's build fails
if it depends on that module. A shared signing library is therefore forbidden by the boundary that lets the
runner touch a daemon at all — and it would also be the weaker arrangement. Two implementations checked
against each other and against fixed vectors can disagree loudly; one implementation agreeing with itself
proves nothing.

The preimage was additionally implemented a third time, in Python, from the contract document alone. All three
produce byte-identical output, and the committed vectors are fixed to that value.

### Payload digest, and the command binding

```
payloadDigest = "sha256:" + hex(SHA-256(canonical preimage))
signature     = Ed25519(canonical preimage)
```

Both over the same bytes. The verifier recomputes the preimage from the parsed fields and checks both. It never
verifies a signature over a digest the document supplied — that would authenticate the digest field rather
than the evidence, and a document could then carry a genuine signature over a digest describing something else.

`ExecutionCommand.sandboxSecurityProfile.assessmentDigest` is unchanged in shape and now carries this
authenticated payload digest. A command names the exact evidence its authorization relied on.

### Runtime subject, and validating it

The property being attested is host-scoped; the old attestation was effectively deployment-scoped. The subject
is an **opaque operator-assigned label**, configured on both sides — the producer stamps it, the control plane
holds the set of subjects it will accept.

Not a hostname, MAC address, machine serial, cloud instance id, or host path: the artifact travels, and each
of those names the machine to whoever reads it. Not "some non-empty string" either — a control plane that
merely displayed the subject would let one signature authorize every runtime that key ever signed for.

### Runtime generation, and its honest limit

`runtimeGeneration` is derived from the container runtime's own opaque instance identity, hashed with domain
separation so nothing host-identifying is published.

**What it gives:** attestations from different runtime instances are distinguishable, and the value is inside
the signature so it cannot be moved onto evidence from elsewhere.

**What it does not give: reboot invalidation.** A daemon's instance identity ordinarily survives a host
restart, so an attestation stays verifiable across one. This is stated rather than papered over. Freshness is
bounded by `assessedAt` and the configured maximum age, and by nothing else.

### Freshness, and what a signature does not prove

A signature proves origin and integrity. It does **not** prove freshness, and a valid attestation can be
replayed until its age or subject conditions reject it. `assessedAt` comes from the producer's clock and is
compared against the control plane's; a bounded future-skew tolerance of one minute corrects for ordinary
drift between two hosts rather than granting slack. The maximum age is unchanged from ADR-023 and is
deliberately not widened because producing signed attestations is more operationally involved than typing one.

### v2 is refused

No migration window, no `allow-unsigned` flag, no `skip-signature-verification`, no development-trust-all. The
platform serves no tenant execution, so this is the right moment for the breaking change and the wrong moment
to build a fallback that would outlive its reason. The v2 record is deleted from the source tree; there is no
code path to re-enable.

### The runner refuses independently

The control plane verifies and binds a digest into the command. The runner separately holds the evidence
describing its own runtime and refuses a command naming anything else. That defeats a command reaching the
wrong place — routed to another runner, or issued before this one was re-assessed — which control-plane
verification cannot notice. The runner does **not** verify the signature: it is the party that produces
attestations and holds the signing key, so a signature checked against its own key would prove nothing.

### CI evidence is not deployment evidence

The same producer runs in CI and on a real runner host. A CI attestation says the mechanism works on a
GitHub-hosted runner; it says nothing about the host that will execute anything. Its runtime subject differs,
and a control plane accepts a subject only if an operator configured it, so a CI artifact cannot authorize
anything anywhere.

## Consequences

An operator must now produce an attestation by running a task on the assessed host, and must configure a
verification key and an accepted runtime subject. A deployment that does neither authorizes no execution —
which was already true of a deployment with no attestation, and is now true for two more reasons.

Every existing attestation stops being accepted. That is the intended effect of a schema change to a security
document, and it fails closed.

The producer signs failures truthfully rather than refusing to serialize. A signed failure is authentic
evidence that authorizes nothing, and it is more useful to an operator than an artifact that does not exist.

## Residual risks

- **Replay within the freshness window.** A valid attestation remains usable until it ages out. Signatures
  provide authenticity, not recency, and this is bounded by maximum age and runtime subject rather than by any
  one-time-use semantic.
- **No reboot invalidation.** See runtime generation above. Documented rather than claimed.
- **The signing key lives on the runner host.** Anyone who can read that file can mint attestations for that
  runtime. That is the same party who controls the daemon, so it is not a new boundary — but it means the
  attestation authenticates *the producer host*, not some external authority.
- **Curve enforcement is jointly covered.** The algorithm-specific `KeyFactory` and the explicit curve check
  guard the same input, and only the first to fire can be observed. Both are kept because they rest on
  different things — a provider behaviour and a property of the parsed key — and neither is claimed as
  independently proven.
- **A compromised runner can attest anything about itself.** Signing moves the boundary from "can type" to
  "holds the private key". It does not make a hostile runner honest, and nothing here claims otherwise.

## What this does not change

**Docker remains a shared-kernel runtime, and ADR-022's stronger-runtime prerequisite is still outstanding.**
This ADR improves the authenticity of evidence about the sandbox boundary. It does not strengthen that
boundary. A perfectly signed attestation describing a shared-kernel container is a trustworthy statement about
a boundary that is still not approved for hostile tenant code.

No Karate runtime exists. No `FeatureRevision` source enters a sandbox. No production secret provider exists
and no tenant secret is delivered anywhere. Nothing in this decision moves any of them.
