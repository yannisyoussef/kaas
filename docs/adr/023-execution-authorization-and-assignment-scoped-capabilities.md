# ADR-023: Execution authorization and assignment-scoped capabilities

## Status

IMPLEMENTED for authorization, capability issuance, and command production. Commands were not executed when this
was written; [ADR-024](024-synthetic-execution-lifecycle.md) now executes them, against a platform-owned
synthetic workload. Nothing in this decision changed — what changed is that something acts on it.

## Context

Claiming a run established one fact: this worker instance owns this infrastructure attempt. ADR-021 was careful
to say that ownership grants no execution authority, and left the question of what would.

The tempting answer is that it needs no separate answer — that a worker holding a live lease is, by definition,
allowed to execute. That is wrong for a reason worth stating plainly: the conditions that make execution safe
are not the conditions that make ownership valid, and they can stop being true while ownership continues. A
worker can legitimately hold an attempt at a moment when the run has been cancelled, when the sandbox on its
host cannot be shown to confine anything, when the egress policy the run needs is one no launcher can enforce,
or when the run binds secrets the platform has no way to supply. Ownership answers "who"; none of those is a
question about who.

## Decision

Execution requires a second, independent decision, taken against authoritative state at a single instant, bound
to exactly one assignment, and expiring.

### CLAIMED is necessary and not sufficient

`ExecutionAuthorization` is that decision. It is issued only when every one of the following holds, checked
under the same row lock every other writer that touches ownership takes, in the same order they all take it:

the run is `CLAIMED`; the attempt named is the current one; the assignment is held by this worker at this epoch;
the lease has not lapsed; the sealed snapshot exists and is small enough to deliver; a sandbox security
assessment is present and trustworthy; the egress policy is one a launcher can actually enforce; and the run's
secret requirements can be satisfied.

Every step fails closed, and the order puts the cheapest and most specific refusals first — a stale assignment
learns nothing about the deployment's security posture.

### Authority is assignment-scoped, not run-scoped

Every row carries an attempt and an assignment epoch, and the uniqueness is on the pair. Authority from
attempt 1 / epoch 1 must never authorize attempt 1 / epoch 2, and keying anything on the attempt alone would
make that unenforceable later. Worker identity is checked together with the epoch at every point, because
either alone leaves a hole: an epoch alone lets any worker act as the current owner, an identity alone lets a
replaced worker act under an assignment it has lost.

### Expiry is not revocation

The database can enforce the ordering of instants. It cannot enforce that a lease is still live, because that
is a fact about the future at the moment of insertion. So **every redemption revalidates authoritative state**.
A capability issued a second before a run was cancelled has an unexpired TTL and must fail; so must one whose
lease lapsed, whose assignment was fenced, or whose epoch was superseded. Expiry bounds the damage from a
leaked token. Revalidation is what makes fencing effective.

### The authorization follows the lease rather than freezing against it

`authorization.expiresAt` is the earlier of a server-controlled TTL and the current lease expiry, so the
invariant `capability ≤ authorization ≤ lease` holds by construction. The first implementation froze that value
at issuance, and that was a liveness dead end: a lease is thirty seconds that a healthy worker renews
indefinitely, so the authorization died half a minute after issuance while its worker was perfectly healthy —
and the uniqueness on the assignment made a replacement impossible. A re-authorization request therefore
**re-anchors** the window forward against the current lease. This cannot widen authority: the value is
recomputed from the live lease, the trigger refuses a backwards move, and redemption revalidates regardless.

### Capabilities rotate; the command does not name them

A retry mints a fresh capability and revokes the previous one in the same transaction, so at most one live
capability of each type exists per authorization — enforced by a partial unique index, not only by lock
discipline. Ten retries produce ten tokens of which nine are already dead, rather than ten that all work.

It follows that a capability identifier cannot appear in the immutable command: it would be stale from the
second request onward. Capability identity belongs to the delivery envelope, which is true for one response
only. This is the concrete form of a more general rule the command digest now enforces — *a field the digest
cannot cover must not be emitted* — because a field a consumer is told to verify, and cannot, is worse than a
field that is absent.

### The security gate reaches the control plane as evidence, not a flag

The hostile-execution gate lives in `services/runner`, which holds container-runtime access, and the control
plane is build-guarded against ever depending on it. The gate's verdict therefore crosses as a document.

It is deliberately not a boolean. A passing attestation must enumerate every mandatory control with its verdict,
and the control plane checks that against the set it independently requires, with **exact equality in both
directions**: containment would let a truncated document pass by omitting the control it failed, and would let
this build accept an assessment produced for a weaker control set. When the runner gains a control, every
existing attestation stops satisfying the control plane until a fresh one is produced. Silence is never a pass.

The document arrives as deployment configuration. No endpoint accepts one — asserted by an architecture rule,
not only by inspection — so nothing that authenticates to this service can assert its own security posture.

### Only enforceable network policy may be authorized

`NetworkPolicyRevision` is platform-owned and immutable. `DENY_ALL` is enforceable, because the sandbox gives a
container no network and the probe demonstrates it from inside. `ALLOWLIST` is modelled so the policy model can
be built toward it, and is **refused** at authorization as `NETWORK_POLICY_NOT_ENFORCEABLE`. A policy that
silently degraded to something weaker when its intended enforcement was unavailable would be worse than no
policy, because the run would appear to have egress control that nothing was applying.

### Secret-bearing runs fail closed

KaaS stores secret metadata and has never stored a secret value. `UnavailableSecretValueProvider` is the
production implementation and refuses; a run that binds secrets is denied `SECRET_PROVIDER_UNAVAILABLE` before
any capability exists. Issuing a command promising secrets nothing can deliver would move the failure past the
point where a sandbox had already started, for a reason nobody recorded.

The capability envelope is nonetheless built now — enumerated scope rather than a scope expression, tenant-scoped
by composite foreign key, and a hard invariant that a secret-bearing run cannot produce a command with no secret
capabilities. That last one exists because the day a provider reports available, the refusal above stops firing
and nothing else would notice secrets being silently dropped.

## Consequences

**Only secret-free runs can reach an ExecutionCommand in this slice, and no command is executed.** That is the
intended end state: the authority composition is proven correct before anything acts on it, and there is no
window in which an incomplete design is one configuration change away from running user content.

The run stays `CLAIMED`. There is no transition to `PROVISIONING`, no publication to a broker, and no path from
a command to the sandbox launcher.

Ordinary Karate runs are not made executable by this slice, because most of them need network access to a target
API and the only enforceable policy is deny-all. That is correct rather than unfortunate: authority composition
comes first, constrained egress comes next.

## Alternatives considered

**A boolean approval flag.** Rejected as configuration optimism wearing the costume of evidence, and the first
thing an attacker looks for.

**Trusting a worker's assertion that the gate passed.** Rejected outright: a component that both performs a
check and declares the result of that check has not been checked.

**Importing the gate's control set from `services/runner`.** Rejected because it would mean the control plane
depending on the module holding daemon access. The set is duplicated instead, and the duplication is guarded by
a shared contract file with a test on each side, so the two cannot drift silently.

**A signed attestation.** Genuinely better and not done here. It would convert "trusts whoever controls
configuration" into "trusts whoever controls the gate's signing key" — a real reduction, because configuration
is held by more people and more systems than a signing key is. It needs a producer and a key-distribution story,
and is named as a prerequisite below rather than half-built.

**One-time capabilities.** Rejected. A worker legitimately retries a download after a connection reset, and a
capability that self-destructed on the first attempt would turn an ordinary network hiccup into a failed run —
which operators would work around by requesting fresh authorizations in a loop, producing more live tokens
rather than fewer. Short windows, assignment fencing, and per-redemption revalidation do the work; the redemption
ceiling bounds amplification rather than being the control.

**Object storage for source bundles.** Deliberately not introduced. Security semantics should not depend on it,
and the immutable FeatureRevision rows already in PostgreSQL are sufficient at this scale.

## Residual risks

- **The attestation is deployment-scoped while the property it attests is host-scoped.** One assessment, produced
  by CI, authorizes an entire fleet for its freshness window. `runtime` is recorded but not compared, and
  `probeImageDigest` is not matched against the image a runner would actually use. Node binding needs a
  worker-to-node identity that does not exist yet.
- **No producer exists for the attestation.** The gate returns a structured assessment and nothing serializes it,
  so an operator must hand-author the document today. Named as a prerequisite.
- **The command digest is unkeyed.** It establishes semantic identity and integrity against partial modification.
  It is not a signature and confers no authenticity.
- **Nothing reconciles stale authority in the background.** An authorization is withdrawn when the next request
  discovers the run is no longer claimed; until then the row reads live to anything querying it directly. It
  grants nothing — redemption revalidates — but a durable record that disagrees with the decision it records is
  one that will eventually be believed.
- **Retention.** Authorizations, capabilities, and commands are undeletable, and a command duplicates the run's
  non-secret configuration. There is no purge path, which makes the outbox and CREATED-run retention topic
  larger rather than smaller.
- **The archive is built in heap inside the redemption transaction.** Bounded now by a snapshot-size check at
  authorization, but streaming it outside the lock is the correct shape.

## Prerequisites before a command may be executed

1. A producer for the attestation, and a signature over it with a pinned key.
2. Source capability delivery into a sandbox, which means the sandbox accepting content for the first time.
3. Secret capability redemption against a real provider, with the envelope this slice built.
4. Constrained egress, so that policies other than deny-all become enforceable and ordinary Karate runs become
   possible at all.
5. `CLAIMED → PROVISIONING`, and the command-delivery protocol that transition implies.
6. A stronger kernel boundary than shared-kernel Docker, per ADR-022, which this slice does not revisit and does
   not satisfy.
