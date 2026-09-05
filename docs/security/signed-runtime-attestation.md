# Signed runtime security attestation

**Status: IMPLEMENTED and VALIDATED.** Schema `kaas.sandbox-security-attestation.v4` is required, and v3
is refused exactly as v2 is. The
unsigned v2 document is refused and its model is deleted from the source tree.

## What a signature here does and does not mean

Three sentences, because every failure of a design like this comes from blurring them.

> **A signature authenticates origin and integrity.** These control results came from a holder of a key this
> deployment pinned, and no byte of them has changed since.

> **A signature does not prove freshness.** A valid attestation can be replayed until it ages out. Recency is
> bounded by `assessedAt` and the configured maximum age, and by nothing else.

> **A signature does not make Docker a stronger isolation boundary.** It improves the authenticity of evidence
> about the sandbox. The sandbox is unchanged, shares the host kernel, and remains not approved for hostile
> tenant code — ADR-022's prerequisite is still outstanding.

## The chain

```
  REAL RUNTIME (a runner host, or a CI runner)
        │
        ├── HostileExecutionSecurityGate ──► 17 mandatory control observations
        │                                    (the set is SCOPED TO THE RUNTIME: see ADR-028)
        └── EgressEnforcementGate ─────────► 5 egress control observations + the proxy image it used
                        │
                        ▼
        SandboxSecurityAttestationProducer          services/runner
          • takes ASSESSMENT OBJECTS, never a verdict map
          • binds probe image digest, proxy image digest, runtime subject,
            runtime generation, profile version, assessment instant
          • builds the canonical preimage
          • signs it                     ◄── Ed25519 PRIVATE key, producer side only, never travels
                        │
                        ▼
              signed attestation artifact
                        │
                        │   transported unchanged, as deployment configuration.
                        │   There is NO endpoint that accepts one.
                        ▼
        SandboxSecurityAttestationVerifier          apps/api
          1. strict parse                  ─┐
          2. schema is v4                   │  a document that fails any of these
          3. exact property set             │  is refused with a CATEGORY and nothing
          4. algorithm is exactly ED25519   │  drawn from its contents — it is not yet
          5. resolve ONE key by keyId       │  authenticated, so quoting it would repeat
          6. reconstruct the preimage       │  attacker-influenceable text
          7. recompute + compare digest     │
          8. verify signature              ─┘   ◄── Ed25519 PUBLIC key, pinned by the operator
                        │
                        ▼   only now is anything read as a verdict
          9. runtime subject is one this deployment accepts
         10. not stale, not implausibly future
         11. profile matches the one execution would use
         12. exact control coverage, every control PASS
                        │
                        ▼
              ExecutionAuthorization
                        │
                        ▼
              ExecutionCommand binds the authenticated payloadDigest
                        │
                        ▼
              the runner independently refuses a command
              naming evidence that does not describe it
```

## What replaced what

| | Before (v2) | Now (v3) |
|---|---|---|
| Who states the verdicts | an operator, by typing | the gate, by observing |
| What the digest proves | the document matches its own digest | nothing on its own; it is recomputed and compared |
| What authenticates it | nothing | Ed25519 over the canonical preimage |
| Who may vouch for it | anyone who can write the config | a holder of a key the deployment pinned |
| Which runtime it describes | unstated | an opaque subject, validated against a configured set |
| Which images it describes | the probe only | probe **and** egress proxy, by digest |
| Can the control plane mint one | yes, trivially | no — it holds no private key, asserted structurally |

## Trust boundaries

| Party | Holds | Cannot |
|---|---|---|
| Tenant | nothing | submit an attestation; there is no endpoint |
| Worker | an assignment-scoped capability | register a verification key; nominate a signer |
| Runner host | the **private** signing key | verify anything on the control plane's behalf |
| Control plane | the **public** verification keys | produce an attestation it would then verify |
| Operator | the artifact and the configuration | author a verdict — the producer reads them off the gate |

The operator transports the artifact. That is the same trust domain as the database credentials and the JWT
issuer, and emphatically not the tenant's or the worker's.

## Where the keys live

- **Private key:** a file on the assessed runtime, read by the producer. Not an environment variable, which is
  visible in `/proc/<pid>/environ` and inherited by children. Not a process argument, which is visible in `ps`
  to every user on the host. Never in a command, a database, a broker, a container label, a metric, a log, or
  the attestation itself.
- **Public keys:** deployment configuration on the control plane, as `keyId=base64Spki` entries. Never fetched
  from the document, a URL it names, tenant configuration, a worker request, or a remote JWKS.

**A `keyId` is not authority.** It selects one pinned key. Unknown ids refuse, and the verifier never tries
the other trusted keys — a key an operator removed must stop working, including for a document that stopped
naming it.

## Rotation

Pin the next key alongside the current one, switch the producer, then remove the previous key. No window
exists in which no valid attestation is accepted. There is no registration API: rotation is an operator action
on configuration.

## Failure behaviour

| Situation | Result |
|---|---|
| No verification key configured | execution authorization unavailable; **the application still starts** |
| Malformed key configuration | same — a bad signing key must not take down read-only endpoints |
| No attestation configured | `ABSENT` → no execution |
| Not a v4 document | `UNSUPPORTED_SCHEMA` → no execution, no unsigned fallback, and v3 is refused as v2 is |
| Profile version and `sandboxRuntime` disagree | `RUNTIME_MISMATCH` → the document contradicts itself about which boundary produced it, and no reading of it is the truth |
| Unknown `keyId` | `UNKNOWN_KEY` → no execution |
| Edited payload | `DIGEST_MISMATCH` → no execution |
| Wrong signature | `INVALID_SIGNATURE` → no execution |
| Evidence for another runtime | `WRONG_SUBJECT` → no execution |
| Aged out | `STALE` → no execution |
| A control did not pass | `CONTROL_FAILED` → no execution |

Every one is a refusal. There is no configuration that turns any of them into a pass, and adding one —
`allow-unsigned`, `skip-signature-verification`, `development-trust-all` — is explicitly out of scope
forever, not merely for this slice.

## CI evidence versus deployment evidence

They use the same producer and the same contract. They are **not interchangeable**.

- A **CI attestation** says the mechanism works on a GitHub-hosted runner. Its runtime subject is
  `kaas.runtime.github-actions`. It is release evidence.
- A **deployment attestation** describes the host that will actually execute something. Its subject is
  whatever the operator assigned that runtime.

A control plane accepts evidence only for subjects an operator configured, so a CI artifact cannot authorize
anything anywhere — not because anybody remembered to prevent it, but because the subject would not match.

## Residual risks

- **Replay inside the freshness window.** A valid attestation stays usable until it ages out.
- **No reboot invalidation.** `runtimeGeneration` distinguishes runtime *instances*; a daemon's identity
  ordinarily survives a host restart, so an attestation remains verifiable across one. Stated rather than
  claimed away.
- **The signing key sits on the assessed host.** Whoever can read it can attest anything about that runtime.
  That is the same party who controls the daemon — signing moves the boundary from "can type" to "holds the
  key", and does not make a compromised host honest.
- **Curve enforcement is jointly covered**, not independently proven; see ADR-027.
- **Docker is still a shared-kernel boundary.** Nothing here changes it.
