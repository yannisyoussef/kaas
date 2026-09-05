# ADR-025: Execution egress remains DENY_ALL until it can be enforced

## Status

ACCEPTED, and **PARTIALLY SUPERSEDED by [ADR-026](026-enforceable-assignment-scoped-execution-egress.md)**.

This document remains the record of why egress was refused rather than approximated, and its analysis of why
the weak design cannot be made safe is unchanged and still load-bearing. What it no longer describes is the
current state: a trusted egress proxy now exists, and `ALLOWLIST` is enforceable for trusted synthetic
execution.

Two of the eight requirements below were found to be **technically incorrect as written** and are corrected in
ADR-026. Both corrections preserve or strengthen the property; neither weakens an invariant.

- **Requirement 1** said an internal network has no default route. Measured, it does — to a gateway that cannot
  forward. The real invariant is that no reachable target route exists except through the proxy.
- **Requirement 2** implied an alternating public/private hostname must always be refused. Under a correct
  one-resolution-per-connection algorithm that would require detecting an answer that was never queried. The
  real invariant is that the address security-checked is the address connected to, classified afresh per
  connection.

The rest of this document is preserved as written, as the historical record of the decision it made.

## Context

Most useful Karate suites reach a target API. A platform that cannot let a test make an outbound request is a
platform most tenants cannot use, so constrained egress is not optional — it is the next thing that has to
exist.

It is also the single most dangerous thing this platform will build. A sandbox with egress is a machine that
makes network requests on behalf of untrusted content, from inside the platform's own network. Getting it
slightly wrong turns every test run into a server-side request forgery primitive pointed at the platform's own
infrastructure, and the failure is silent: the tenant's test passes either way.

The pressure to ship a weak version is real, and the weak version has an obvious shape — validate the URL
against the tenant's allowlist in application code, then let the sandbox make the request normally. **That
design cannot be made safe.** The check and the connection are separated in time and space, so the name
resolved at validation is not necessarily the address connected to; and even if it were, the sandbox retains a
normal route to the network, so nothing forces the request through the check at all. It is a suggestion, not a
control.

## Decision

**Egress stays `DENY_ALL` until a mechanism exists that a sandbox cannot route around.** `ALLOWLIST` is refused
rather than approximated.

Refusal, not degradation, is the load-bearing part. A run that appeared to have egress control which nothing
was applying would be worse than a run with no egress at all, because somebody would rely on it. The refusal
happens twice and independently:

- the control plane denies authorization with `NETWORK_POLICY_NOT_ENFORCEABLE`;
- the runner refuses the command with "cannot enforce the network policy ALLOWLIST", in a module that
  structurally cannot call the control plane's code.

Mutation testing found that the runner's refusal was, until this slice, covered by nothing at all: every
negative test tampered with a command, which changed its digest, so the digest check refused first and the
policy check below it was never reached. Deleting it killed no test. That is now fixed with correctly-digested
but unenforceable commands, and the check is independently killable.

## What kaas-13 must satisfy before ALLOWLIST can be enforceable

These are requirements, not suggestions. An implementation that does not meet all of them is not an allowlist.

### 1. The sandbox must have no route except the proxy

The sandbox attaches to a Docker `--internal` network. A trusted proxy container attaches to *both* that
network and one which can reach targets. The sandbox cannot route around the proxy because nothing else is
reachable — not because it is asked not to.

**Corrected in kaas-13, against measurement.** This requirement previously said the internal network has "no
default route". That is false: Docker's `--internal` network *does* install a default route to a gateway which
simply cannot forward externally. The property was right and the description of it was wrong, which is worse
than saying nothing — a reader checking the claim finds a route that is supposed to be absent, and then either
weakens the requirement or concludes the topology is broken.

The invariant is **not** the absence of a route. It is that **no target is reachable through it**, measured
from inside the sandbox by the trusted probe: one global address, one live interface, one default route, and
every attempted destination — public, private, metadata, link-local, IPv6 metadata, the Docker host, and the
discovered gateway — unreachable.

**Test to write:** with the proxy stopped, every outbound attempt from the sandbox must fail. If anything
succeeds, the sandbox has a second route and the whole design is void.

### 2. The proxy resolves DNS itself, and connects to the address it resolved

**Measured in kaas-13:** a sandbox on the internal network cannot resolve external names. Docker's embedded
resolver is reachable there and forwards to the host's resolvers, which an internal network cannot reach, so
resolution fails. That is a property of Docker's networking rather than of anything this repository controls,
so it is asserted from inside the sandbox on every run — a Docker change that made those forwards succeed would
be a silent exfiltration channel nothing else would notice.

The sandbox must not resolve names. If it resolves and the proxy connects, or the proxy resolves twice, a name
that returns different addresses on successive lookups defeats the check. Resolution and connection must be one
step over one address.

**DNS rebinding is the specific attack.** A name whose TTL is zero and which alternates between a public
address and `169.254.169.254` passes any check performed before connection. **Test to write:** a target whose
DNS answer changes between the check and the connection must be refused, with an authoritative server actually
serving the alternating answers — not a mock.

### 3. Private, reserved, and link-local addresses are refused after resolution

The refusal is on the **resolved address**, never on the hostname. Minimum denied set: RFC 1918, loopback,
link-local including `169.254.0.0/16` and therefore the cloud metadata endpoints, unique-local IPv6, IPv4-
mapped IPv6, `0.0.0.0/8`, multicast, and broadcast. IPv6 must be handled explicitly or disabled outright —
a v4-only check on a dual-stack host is not a check.

**Test to write:** a hostname that resolves to a private address must be refused. Include the IPv4-mapped IPv6
form of a private address, which is the case that a naive check misses.

### 4. Redirects are re-checked, every hop

A redirect is a new destination chosen by the target, not by the tenant. Every hop must be re-validated against
the allowlist and the address rules, with a bounded hop count. The safest implementation does not follow
redirects at all and returns them to the caller.

**Test to write:** an allowed host that redirects to a denied host, and one that redirects to a private
address, must both be refused at the redirect rather than followed.

### 5. Proxy authorization is scoped to one assignment

Proxy credentials must be bound to one run, one attempt, and one assignment epoch, and must stop working the
moment that assignment is fenced — the same revalidation the source-bundle capability already performs. A
shared proxy credential means any sandbox can spend any other sandbox's allowlist.

**Test to write:** a fenced assignment's proxy credential must be refused mid-run, not merely at issuance.

### 6. Proxy lifecycle is tied to the sandbox's

The proxy starts before the sandbox and is destroyed after it, including on every failure path — launch
failure, deadline, cancellation, and reconciler reclaim. A proxy that outlives its sandbox is a standing egress
gateway with nothing attached to authorize it, and the orphan reconciler must reclaim proxies on the same age
basis it reclaims sandboxes.

**Test to write:** kill the runner mid-execution and assert the reconciler reclaims both containers and the
network.

### 7. Failure and reconciliation semantics are defined before the happy path

A proxy that cannot start is an `INFRASTRUCTURE_FAILURE`, not a test failure — the run stops with no test
outcome. A proxy that dies mid-run must not silently restore full egress; the sandbox must lose connectivity,
which the `--internal` network already guarantees. Egress denials must be attributable in the result document
without leaking the resolved addresses of a tenant's internal infrastructure into logs another tenant's
operator can read.

### 8. The proxy image is repository-controlled and digest-pinned

Same rules as the probe image: built from a repository-controlled context, pinned by digest, and covered by the
Gradle input declaration so a change to it cannot leave the test task `UP-TO-DATE`.

## Consequences

Ordinary Karate runs remain non-executable after this slice, and that is the correct outcome rather than a
regression. Authority composition came first, lifecycle second, egress next, engine last.

The eight requirements above are collectively a slice of their own. Attempting them alongside the execution
lifecycle would have meant debugging DNS rebinding and phase deadlines in the same change, and the predictable
outcome of that is a lenient version of whichever one was blocking progress that day.
