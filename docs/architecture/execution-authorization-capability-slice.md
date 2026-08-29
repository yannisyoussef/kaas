# Execution authorization and assignment-scoped capabilities

**Status: IMPLEMENTED for authorization, capability issuance, and command production. NOTHING IS EXECUTED.**

Owning an attempt and being allowed to execute it are two decisions, taken at different moments against
different evidence. ADR-021 established the first. This slice establishes the second, and deliberately stops
before anything acts on it.

## The decision

```
                    TestRun CLAIMED
                          |
                          v
              active assignment?  ── no ──▶  ASSIGNMENT_STALE
       (attempt + epoch + worker, together)
                          |
                          v
                  active lease?     ── no ──▶  LEASE_EXPIRED
                          |
                          v
            sealed snapshot, small enough?  ── no ──▶  RUN_SNAPSHOT_INVALID
                          |
                          v
              security gate PASS?   ── absent ──▶  SECURITY_GATE_UNAVAILABLE
        (every mandatory control,   ── failed ──▶  SECURITY_GATE_FAILED
         exact coverage, fresh)
                          |
                          v
        network policy enforceable? ── no ──▶  NETWORK_POLICY_NOT_ENFORCEABLE
              (DENY_ALL only today)
                          |
                          v
           secrets satisfiable?     ── no ──▶  SECRET_PROVIDER_UNAVAILABLE
      (no production provider exists,
       so any secret-bearing run stops here)
                          |
                          v
                 ExecutionAuthorization
                  (one per attempt+epoch)
                          |
                          +──▶  SourceCapability      (short-lived, rotated per delivery)
                          |
                          +──▶  SecretCapability(s)   (modelled; never issued today)
                          |
                          v
                    ExecutionCommand
                     (immutable, digested)
                          |
                          v
                         STOP
```

**Annotations, because the diagram's most important property is what it does not contain:**

- **NO PROVISIONING.** The run stays `CLAIMED`. `CLAIMED → PROVISIONING` belongs to the slice that can actually
  provision something.
- **NO sandbox invocation.** The hardened sandbox from ADR-022 remains reachable only from its own security
  harness. No command reaches it, and the probe accepts no source, no secret, and no command.
- **NO Karate.** No engine dependency exists anywhere in the graph.
- **NO user-code execution.** Feature source is assembled into a bundle a worker may fetch. It does not enter a
  sandbox, because nothing yet puts it there.
- **NO broker publication.** A command is written to a table and goes no further. The dispatch consumer cannot
  reach it.

## Why every step fails closed

Each condition can stop being true while ownership continues, which is the whole reason this is a separate
decision. A worker legitimately holds an attempt at a moment when the run was cancelled, when the sandbox on
its host cannot be shown to confine anything, or when the run needs secrets nothing can supply. Ownership
answers *who*; none of those is a question about who.

The order is chosen so the cheapest and most specific refusals come first. A caller naming a stale assignment
learns that and nothing about the deployment's security posture.

## What a capability is, and what it is not

A capability is a short-lived bearer token bound to one run, one attempt, one assignment epoch, and one worker.
Its plaintext exists exactly once, in the response that issues it; only a SHA-256 is stored, so a database
backup grants nobody anything.

**Holding one proves nothing about the present.** Every redemption revalidates authoritative state under the
same lock every ownership writer takes: the run is still `CLAIMED`, the attempt still holds this assignment,
this worker still owns it at this epoch, the lease has not lapsed. A capability issued a second before a
cancellation has an unexpired TTL and fails. Expiry bounds the damage from a leaked token; revalidation is what
makes fencing effective.

The clock for all of this comes from PostgreSQL, read **after** the lock is taken. Reading it before was a real
defect: a redemption that waited on a contended run row evaluated its windows against an instant from before
the wait, and served a bundle 550 ms after the capability itself had expired.

## The TTL chain

```
capability.expiresAt  ≤  authorization.expiresAt  ≤  attempt.lease.expiresAt
```

True by construction — each is computed as the earlier of a server-controlled TTL and the bound above it — and
re-established on every request rather than frozen. Freezing it was a liveness dead end: a lease is thirty
seconds that a healthy worker renews indefinitely, so an authorization anchored once died half a minute after
issuance while its worker was fine, and the uniqueness constraint made a replacement impossible. Re-anchoring
moves the window forward against the *current* lease; it cannot widen authority, because the value is recomputed
from live state and redemption revalidates regardless.

## Rotation, and why the command names no capability

A retry mints a fresh capability and revokes the previous one in the same transaction. At most one live
capability of each type exists per authorization — enforced by a partial unique index rather than only by lock
discipline. Ten retries leave one working token, not ten.

It follows that a capability identifier cannot live in an immutable command: it would be stale from the second
request onward. Capability identity belongs to the delivery envelope, which is true for one response only.

That is a specific case of the rule the command digest enforces: **a field the digest cannot cover must not be
emitted.** The digest covers every field the document carries, precisely because the earlier design excluded
several — and three independent reviews produced collisions, including two commands binding one key to different
secrets from different providers.

## The security-gate bridge

The gate lives in `services/runner`, which holds container-runtime access. The control plane is build-guarded
against depending on it, so the verdict crosses as a document rather than a call.

It is not a flag. A passing attestation enumerates every mandatory control with its verdict, and the control
plane checks that against the set it independently requires, with exact equality in both directions. When the
runner gains a control, every existing attestation stops satisfying the control plane until a fresh one is
produced. No endpoint accepts one — an architecture rule asserts that, so it is not true merely by inspection.

The shared control set lives in `packages/api-contracts/mandatory-sandbox-controls.json`, with a test on each
side, because a set two modules must agree on and neither may import is a set that drifts.

**Honest limitations.** The attestation is deployment-scoped while the property it attests is host-scoped: one
assessment authorizes a fleet for its freshness window, `runtime` is recorded but not compared, and nothing
binds it to the node that would run the command. It is unsigned, so it detects a partially edited document
rather than authenticating one. And no producer exists yet — the gate returns a structured assessment and
nothing serializes it, so an operator hand-authors the document today. A signed attestation with a pinned key is
the named next step.

## Network policy

Platform-owned and immutable. `DENY_ALL` is the only revision that exists and the only type any launcher can
enforce. `ALLOWLIST` is defined so the model can be built toward it and is **refused** at authorization rather
than silently degraded, because a run that appeared to have egress control nothing was applying would be worse
than one with none.

This is why ordinary Karate runs are not executable after this slice. Most need to reach a target API. That is
the correct outcome: authority composition first, constrained egress next.

## Secrets

The control plane stores secret metadata and has never stored a secret value. The production provider refuses,
and a secret-bearing run is denied before any capability exists.

The envelope is built anyway: scope is an enumeration rather than an expression, tenant ownership is structural
through composite foreign keys, and a hard invariant fails loudly if a secret-bearing run ever produces a command
with no secret capabilities. That last one matters because the day a real provider reports available, the
refusal stops firing and nothing else would notice secrets being silently dropped.

## Where this sits

| Boundary | Established by | This slice |
|---|---|---|
| Who owns an attempt | ADR-021 claim, epoch, lease | reads it, never changes it |
| Whether a sandbox confines what it runs | ADR-022 gate and probe | consumes its verdict as evidence |
| Whether *this* assignment may execute | ADR-023 | **establishes it** |
| Whether anything actually executes | not yet decided | **deliberately absent** |
