# ADR-026: Enforceable assignment-scoped execution egress

## Status

ACCEPTED. `ALLOWLIST` is enforceable for trusted synthetic execution. A purpose-built KaaS egress proxy is the
only path out of an execution sandbox, and the sandbox has no other route by construction.

This ADR **partially supersedes ADR-025**. ADR-025's decision — that egress stays `DENY_ALL` until a mechanism
exists a sandbox cannot route around — is satisfied rather than reversed, and two of the eight requirements it
recorded were found to be technically incorrect as written and are corrected below. Nothing in it is weakened.

## Context

ADR-025 named eight requirements an allowlist would have to satisfy and stated plainly that the obvious weak
design — validate the URL in application code, then let the sandbox make the request normally — cannot be made
safe. That remains true, and it is the reason for almost every decision here.

Two facts shaped this slice more than any design preference.

**The first is that reachability, not routing, is the property that matters.** ADR-025 requirement 1 said an
internal Docker network has "no default route". Measured, that is false: `docker network create --internal`
*does* install a default route to a gateway. The gateway simply cannot forward. The property was real; the
description of it was wrong, and a description that is wrong in a security document is a description somebody
will one day implement against.

**The second is that a policy check and a socket are separated by a name.** If the address that was classified
is not the object that gets connected to, every classifier in the world is decoration. This turned out to be
almost invisible to testing: replacing "connect to the address" with "connect to the hostname" left the entire
in-process suite green, because both paths fail to connect in a test environment and look identical.

## Decision

### Architecture: a purpose-built narrow proxy

Three options were considered.

**A. A purpose-built KaaS HTTP/HTTPS forward proxy.** Chosen.

**B. Envoy or Squid with a KaaS authorization control plane.** Rejected. Both are excellent and neither is
narrow. The security property required here is not "filter requests" but "this component can do exactly two
things and nothing else": a component sitting on both an untrusted network and a network the untrusted side
cannot otherwise reach is a pivot, and its value to an attacker is the union of everything it can do. Squid
brings caching, ICAP, and a large configuration surface; Envoy brings an admin interface, xDS, and dynamic
reconfiguration. Each of those is a thing that must be turned off correctly and stay off. A configuration
mistake in either is a bypass, and a configuration mistake is not something a test in this repository would
catch.

**C. Something else, narrowly justified.** Nothing else offers exact-address connection with per-request
authoritative revalidation, which is the whole of the mechanism.

The chosen proxy speaks two things: an ordinary forward-proxied HTTP request, and a `CONNECT` tunnel. There is
no SOCKS, no UDP association, no arbitrary port forwarding, no protocol relay, no admin interface, no
configuration file. Every setting arrives in the launcher's environment and none from a request. It holds no
Docker client — its build fails if it acquires one — so a proxy compromise cannot start a container.

### Trust boundary

The proxy is trusted infrastructure. The sandbox is untrusted. The proxy may reach networks the sandbox cannot,
which is precisely why it is deliberately narrow, non-root, read-only-rootfs, capability-less, and holds no
mount and no daemon socket. Trusted is a statement about what it may reach, not a licence to run it
unconstrained.

### Topology: the no-bypass property is structural

```
   sandbox                    proxy                     targets
     |                       /     \                       |
     |    kaas-exec-<id>    /       \    egress network    |
     +-------(internal)----+         +--------------------+
                                      \
                                       \  control plane, DNS
```

The sandbox is attached to **one** network: a per-execution, `--internal` Docker network created for it and
destroyed with it. The proxy is attached to that network **and** to the networks that reach targets, DNS, and
the control plane. The sandbox is never attached to the second set.

That asymmetry is the whole of the no-bypass property, and it is topological — nothing in the sandbox's own
configuration is asked to cooperate, no environment variable is trusted, and a workload that ignores
`HTTP_PROXY` and opens a raw socket reaches nothing. The network's internal flag is read back **from the
daemon** after creation and the network self-destroys if it is not set, because the flag is the entire
guarantee and a guarantee nobody verifies is a comment.

**Correcting ADR-025 requirement 1.** The invariant asserted is not "no default route" but *no reachable target
route exists except through the proxy*. A route to a gateway that cannot forward is present and harmless; a
test asserting its absence would have failed against a correct topology, and the obvious repair for that
failing test would have been to delete it.

### DNS: resolve once, classify, connect to that exact address

The proxy resolves. The sandbox cannot: it has no reachable resolver, which is asserted rather than assumed.

For each connection the proxy sends exactly one `A` query, over TCP, to a launcher-supplied server, with no
cache object anywhere in the path. Every returned address is classified. If **any** of them is outside global
unicast the whole resolution is refused — not merely that address — because an answer set mixing public and
private addresses is the shape of a rebinding attempt, and the conservative reading of an ambiguous answer is
to refuse it. The proxy then connects to one of the `InetAddress` objects it built **from those bytes**.
`InetAddress.getByAddress` performs no lookup, so there is no name left to resolve and no second answer can
reach a socket.

**Correcting ADR-025 requirement 2.** ADR-025 implied an alternating public/private hostname must always be
refused. Under a one-resolution-per-connection algorithm that is not the invariant, and stating it that way
would document a property the implementation cannot prove — it would require detecting a DNS answer that was
never queried. The true invariant is: *the address that is security-checked is the address connected to*, and
each connection is classified independently. A name that answers safely, then unsafely, then safely produces a
success, a refusal, and a success. That is asserted against a real authoritative DNS server that changes its
answers between queries, with the query count measured at the server rather than reported by the application.

Only `A` records are queried, because v1 carries IPv4 only. Querying `AAAA` anyway would either waste the
lookup or refuse every dual-stack destination.

### Address classification: an allowlist of one class

Global unicast is permitted; everything else is refused by default. A blocklist has to stay complete against a
registry that changes, and the failure mode of an incomplete blocklist is a permitted connection. Classification
is performed on **raw bytes**, never on a parsed `InetAddress`: the JDK collapses an IPv4-mapped IPv6 address
into an `Inet4Address`, and that is a property of the JDK rather than of the answer that came over the wire.

Refused: `0.0.0.0/8`, loopback, RFC 1918, link-local (which is what makes cloud instance metadata unreachable
as a consequence rather than a special case), CGNAT `100.64.0.0/10`, multicast, `240.0.0.0/4` including the
broadcast address, the three documentation ranges, benchmarking `198.18.0.0/15`, and the IETF protocol
assignments `192.0.0.0/24` and `192.88.99.0/24`.

### IPv6: refused in v1, but decoded anyway

Option B of the two ADR-025 offered: IPv6 egress is refused outright rather than shipping a half-verified
global-unicast classifier for it. An `AAAA`-only destination does not work, which is a visible failure.

Even so, IPv4 embedded inside IPv6 — mapped, the deprecated compatible form, 6to4, Teredo — is decoded and its
embedded address classified, so `::ffff:127.0.0.1` reports `LOOPBACK` rather than a generic IPv6 refusal. It
would be simpler to refuse all sixteen-byte addresses with one reason. It would also mean that handling gets
written for the first time on the day IPv6 is switched on, which is the worst possible moment for its first
test.

### Policy grammar and canonicalization

An entry is exactly `host:port/scheme`. No wildcard, no CIDR, no port range, no path, no IP literal. Those are
refused at parse time rather than accepted and never matched, because an entry that can never match is one a
tenant believes is protecting them.

Canonicalization is specified in `packages/api-contracts/egress-allowlist-canonicalization.md` and implemented
**independently** by the control plane and the proxy. Neither imports the other: the proxy must not carry a
runtime dependency on control-plane implementation, and a shared library would make a change on one side
silently a change on the other.

Nothing is repaired. `EXAMPLE.com.` is refused with a reason rather than normalized, and that matters more in
the proxy than in the control plane — repairing a request is how a request for a destination nobody authorized
becomes a request for one somebody did.

One default is applied, to a **request** and never to an entry: an absolute-form `http://` URL that omits its
port is read as port 80. That is what `http://` means and there is no second reading; an *entry* with no port
would be a tenant authorizing a destination they did not name, so the control plane refuses it.

### Request authority: two statements must agree

A request states its destination twice — the request line and the `Host` header — and they are reconciled once,
before anything is authorized. A disagreement is refused rather than resolved in favour of either. Duplicate
`Host`, duplicate `Content-Length`, obsolete line folding, bare LF, whitespace before a colon, and
`Transfer-Encoding` are all refused: each is a way for two participants to disagree about where one request
ends and the next begins.

`CONNECT` carries scheme class `HTTPS`. A `CONNECT` to a port allowlisted as `80/HTTP` is therefore denied,
because an opaque byte channel is not the thing that entry authorized.

### HTTPS: no interception

A `CONNECT` tunnel is relayed opaquely. The proxy authorizes the authority, resolves it, connects to the exact
classified address, and then carries bytes; TLS stays end to end and the workload validates the target's
certificate itself. The platform therefore cannot see what crosses an authorized tunnel — and also cannot be
compelled to, and a proxy compromise does not yield tenant traffic. Egress policy is about destinations, not
payloads. This is not a WAF and will not become one.

### Redirects: not followed, and the documentation says so

The proxy hands a `3xx` back untouched. It does not follow redirects on anyone's behalf and does not inspect
`Location`. If the client follows one, that is a **new** proxied request, authorized from scratch. The
enforcement point is the second authorization, and claiming the proxy "follows and validates redirects" would
describe a control that does not exist.

### Assignment-scoped egress capability

An `EGRESS` capability is an execution capability, not a new bearer-token system: it belongs to one
`ExecutionAuthorization`, which already binds organization, project, run, attempt, assignment epoch, worker, and
network policy revision. Its plaintext exists in one response and nowhere else; only a SHA-256 is stored. It is
absent from the command's semantic digest, because the command is immutable and this rotates on every delivery
— a field the digest cannot cover must not be emitted inside it — and absent from labels, metrics, logs, the
outbox, and the inbox.

It travels in the sandbox's environment, and anything delivered into a sandbox must be assumed readable by
whatever runs there. Its protection is therefore not secrecy but narrowness: one execution, one epoch, one
policy, briefly, revalidated against live state on **every** use.

An egress validation is not a redemption. It delivers nothing, and it happens on every request and every tunnel
revalidation — hundreds of times per execution — so counting it against the amplification ceiling that bounds
source and secret capabilities would fence a healthy run within seconds. A database constraint enforces that
distinction rather than leaving it to the service layer.

### Proxy authentication to the control plane

There is no mTLS here and none is claimed. The internal service surface authenticates a signed JWT with a
subject in the reserved `kaas.` namespace. The narrowest truthful improvement was made instead of building a
service mesh: the subject `kaas.egress-proxy` receives an authority that reaches **only** the egress validation
endpoint, and every other platform subject receives the general service authority and not that one. A stolen
proxy credential can ask one question; it cannot advance a phase, submit a result, or mint an authorization for
any run in any tenant.

The endpoint answers `200` whatever the verdict, because a status-coded denial is an oracle, and its body
carries a verdict and a category and nothing else. It does **not** resolve the destination: resolution and
classification belong to the component that opens the socket, and moving them here would reopen the gap between
the address checked and the address used.

### Active tunnel fencing, with a stated bound

Authorizing at `CONNECT` and then relaying is the obvious implementation and it is wrong: no further HTTP
request crosses an established tunnel, so nothing would ever cause the authority to be re-checked, and an
assignment fenced a second later would leave a working channel open for as long as the workload cared to hold
it.

Each tunnel therefore revalidates on a timer and closes both sockets the moment the answer stops being yes —
including when the answer cannot be obtained at all.

**The bound is a polling bound and is documented as one.** Revocation is not immediate:

```
assignment fenced at T  ->  tunnel unusable by T + revalidationInterval + authorizationTimeout
```

The interval is monotonic, measuring elapsed time rather than a deadline, so it is immune to host clock
adjustment. The absolute question of whether a capability has expired belongs to the control plane, which owns
the authoritative clock and answers it on every revalidation — the proxy never compares its own wall clock to a
database-generated expiry.

### Fail closed, everywhere

New requests with no reachable authority are refused with `503`, not `403`: "I could not ask" and "the answer
was no" are different facts and an execution's evidence must be able to tell them apart. Existing tunnels close
at the next revalidation. There is no cache, no retry past the timeout, and no "last known good authorization".
Availability loss is preferable to carrying traffic on an authority nobody can confirm.

### Lifecycle and reconciliation

The proxy is started and proven ready **before** the sandbox exists; if it cannot start, the sandbox is never
created and the execution is an infrastructure failure. There is no degraded mode.

If the proxy dies mid-run the sandbox does not regain connectivity — there is simply nothing at the other end,
which is topological rather than reactive. Detection exists to classify the execution truthfully, not to
contain it.

Reconciliation covers sandboxes, proxies, and per-execution networks, containers first because a network with
an endpoint attached cannot be removed. A network is reclaimed only when it carries this platform's labels,
is empty, **and** is older than the abandonment window — the age rule exists because a network is created
before anything joins it, so a healthy execution briefly owns an empty one. There is no "remove every unused
network" operation and there will not be.

Leaving an orphaned proxy behind is the single worst artefact this component could produce: a running gateway
holding a service credential, attached to the target network, with nothing left that could stop it.

### Image provenance

The proxy image is built from a repository-controlled context assembled by the build — the Dockerfile from
source control plus exactly the jars on the proxy's own runtime classpath — and identified by the full digest
of what was built, never a tag. Its base is pinned to a multi-architecture digest so the same identity resolves
on both CI and development machines; pinning a platform-specific manifest would build on one and fail on the
other, and the usual repair for that is to un-pin. No caller can choose the image.

### Enforceability is measured per deployment

`ALLOWLIST` being an enforceable *type* is necessary and not sufficient. Whether a particular host can enforce
it is a separate question with a separate answer, because everything it rests on is a property of the machine:
whether the runtime creates a network that is genuinely internal, whether the image builds, whether a proxy
comes up, whether a sandbox placed there is left with no route.

So the sandbox security assessment carries a second, smaller set of controls — `EGRESS_NETWORK_INTERNAL`,
`EGRESS_PROXY_IMAGE_PINNED`, `EGRESS_PROXY_READY`, `EGRESS_NO_DIRECT_ROUTE`, `EGRESS_PROXY_FAILS_CLOSED` — and
the control plane refuses an allowlist execution unless the assessment covers exactly that set with every one
passing. They are produced by doing each of those things and observing the result, not by a flag. An assessment
that makes no egress claim is a refusal, because absent evidence fails closed.

They are a **separate** set from the mandatory controls, required only for `ALLOWLIST`. Demanding them of a
`DENY_ALL` run would make a sandbox that wants no network depend on the egress subsystem being healthy, which
increases the attack surface of exactly the runs that were supposed to have none. `DENY_ALL` keeps the simpler
proven network-disabled path.

The runner refuses independently, and both refusals are kept. A control plane that authorized an allowlist a
worker could not apply would produce a run with egress nobody was constraining, and the worker is the only
party in a position to know what its own host can do.

## Consequences

An ordinary Karate suite still cannot run, because there is still no Karate. What has changed is that the
reason is no longer egress.

A tenant selects a policy at the project level; a run snapshot pins it at creation, in the same statement that
creates the snapshot, so a configuration change cannot alter what an already-created run may reach. Policies
are platform-authored: a tenant selects, an operator creates. That is a real limitation and the next product
step.

The attestation schema moved to v2, so every existing attestation stops being accepted until a fresh one is
produced. That is fail-closed and consistent with the rule ADR-023 already established.

## Residual risks

- **Azure instance metadata at `168.63.129.16` is global unicast** and is therefore not refused by the address
  classifier. The generic rule does not cover it, and special-casing one provider's address would invite the
  belief that the list is complete. Deployments on that platform need a network-level control.
- **The attestation is unsigned and deployment-scoped**, while the property it attests is host-scoped. It
  detects a partially edited document rather than authenticating one, and no producer serializes it — an
  operator hand-authors it today, now with five more controls to get right. A signed attestation with a pinned
  key remains the named next step, and it matters more than it did.
- **The proxy's service credential is visible to anything with daemon access**, because it is in the container's
  environment. Daemon access is already the highest privilege on a runner host, so this is not a new boundary,
  but it is not nothing.
- **Revocation is bounded, not immediate.** A fenced assignment's tunnel remains usable for up to one
  revalidation interval plus one authorization timeout.
- **The gate proves the mechanism can be instantiated, not that the proxy honours a decision.** Assessing the
  latter would require the gate to hold a control plane, which the module boundary forbids; it is proven by the
  test suites instead, which is the right place for a property of the code rather than of the machine.
- **One `A` query per connection is not DNSSEC.** TCP removes off-path spoofing and nothing more.

## Why tenant code still does not execute

Nothing here changes what runs. The workload is still `KAAS_SYNTHETIC_V1`, repository-controlled, with no
Karate runtime anywhere in the dependency graph, no `FeatureRevision` source entering the sandbox, and no
tenant secret. ADR-022 named a stronger runtime — gVisor or a microVM — as the prerequisite for admitting user
content, with its gate re-run against that runtime as the acceptance criterion. This ADR does not revisit it
and does not satisfy it.
