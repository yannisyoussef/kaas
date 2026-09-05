# Execution egress policy

**Status: DENY_ALL and ALLOWLIST are both enforceable. A trusted KaaS egress proxy is the only path out of an
execution sandbox. No Karate runtime exists; the workload is still repository-controlled and synthetic.**

This document states what is enforced today and what is not. The decisions are
[ADR-025](../adr/025-execution-egress-remains-deny-all.md), which refused an allowlist until it could be
enforced, and [ADR-026](../adr/026-enforceable-assignment-scoped-execution-egress.md), which enforces it and
corrects two of ADR-025's wordings. The topology and evidence map is
[enforceable-execution-egress](../architecture/enforceable-execution-egress.md).

## Two paths, deliberately different

```
DENY_ALL                                ALLOWLIST
--------                                ---------
sandbox, network disabled               sandbox on ONE per-execution --internal network
        |                                       |
        X  nothing reachable                    |  its only reachable peer
                                                v
                                        trusted egress proxy ---> control plane (may this, now?)
                                                |            ---> DNS (one A query, TCP, no cache)
                                                |            ---> classify EVERY answer
                                                v
                                        connect to that EXACT address
                                                |
                                                v
                                        authorized target
```

`DENY_ALL` keeps the simpler proven path on purpose. A run that needs no network must not acquire a dependency
on the egress subsystem being healthy, and it is issued no egress credential at all — a capability that exists
is a capability that can leak.

## What is enforced today

**For `DENY_ALL`:** the sandbox runs with its network disabled at the container runtime. Nothing inside it can
reach anything, and the hostile-execution probe asserts that from inside the sandbox on every gate run.

**For `ALLOWLIST`:**

| Property | How |
|---|---|
| The sandbox has no route to any target | it is attached to exactly one `--internal` network; the proxy is attached to that one and to the target networks; the sandbox never is |
| The internal flag is real | read back **from the daemon** after creation; the network self-destroys if it is not set |
| The sandbox cannot resolve names | no resolver is reachable from that network, asserted rather than assumed |
| Only the named destination is permitted | byte equality on `host:port/scheme`, over the whole list; never a suffix or prefix test |
| The address checked is the address used | one resolution per connection, every answer classified, connection made to an `InetAddress` built from those bytes |
| Non-global addresses are refused | an allowlist of one class — global unicast — not a blocklist |
| Authority is assignment-scoped | revalidated against live state on every request and every tunnel revalidation |
| An established tunnel cannot outlive fencing | closed within `revalidationInterval + authorizationTimeout` |
| A dead control plane does not mean open egress | new requests refused with `503`; existing tunnels closed |
| A dead proxy does not restore connectivity | there is nothing at the other end; containment is topological |

### Measured, not assumed

`docker network create --internal` **does** install a default route, to a gateway that cannot forward. ADR-025
said it did not. The invariant asserted is therefore reachability, not the absence of a route — a test written
against the old wording would have failed on a correct topology, and the obvious repair would have been to
delete it.

## What is refused, and why the refusal is a refusal

`ALLOWLIST` is refused — never quietly downgraded to `DENY_ALL` — when:

| Condition | Refused by |
|---|---|
| the deployment's assessment carries no passing egress controls | control plane, `NETWORK_POLICY_NOT_ENFORCEABLE` |
| this runner cannot instantiate the mechanism on this host | runner, independently, in a module that cannot call the control plane |
| the policy's digest no longer matches its own content | control plane |
| the destination is not in the policy | proxy, `DESTINATION_NOT_ALLOWED` |
| the destination resolves outside global unicast | proxy, `ADDRESS_NOT_GLOBAL` |
| the assignment has been fenced or the lease has lapsed | control plane, `ASSIGNMENT_FENCED` |
| the control plane cannot be reached | proxy, `AUTHORIZATION_UNAVAILABLE`, `503` |

A run that appeared to have egress control which nothing was applying would be worse than a run with no egress
at all, because somebody would rely on it.

## Address classification

Global unicast is permitted. Everything else is refused **by default**, which is the direction that stays
correct as registries change: an incomplete blocklist permits a connection, an incomplete allowlist refuses
one.

Refused: `0.0.0.0/8`, loopback, RFC 1918, link-local `169.254.0.0/16` (which makes cloud instance metadata
unreachable as a consequence rather than a special case), CGNAT `100.64.0.0/10`, multicast, `240.0.0.0/4`
including the broadcast address, the documentation ranges, benchmarking `198.18.0.0/15`, and IETF protocol
assignments.

Classification runs on **raw bytes**. The JDK collapses `::ffff:8.8.8.8` into a four-byte address, and that is
a property of the JDK rather than of what came over the wire.

**IPv6 egress is refused in v1.** An `AAAA`-only destination does not work, which is a visible failure rather
than a silent one. IPv4 embedded inside IPv6 is still decoded and classified, so `::ffff:127.0.0.1` is refused
as loopback rather than with a generic IPv6 reason.

## What this is not

- **Not a WAF.** The enforced boundary is `host + port + transport class`. A tenant may send any method, path,
  query, or body to an authorized destination.
- **Not TLS interception.** A `CONNECT` tunnel is relayed opaquely and TLS stays end to end. The platform
  cannot see what crosses an authorized tunnel — and cannot be compelled to, and a proxy compromise does not
  yield tenant traffic.
- **Not a redirect follower.** A `3xx` is handed back untouched. If the client follows it, that is a new
  proxied request authorized from scratch, and *that* is the enforcement point.
- **Not immediate revocation.** The bound is a polling bound and is written as one.

## What never leaves

Resolved addresses do not appear in cross-tenant operational logs or in any metric label — an address can
describe a tenant's internal topology. Metrics carry only closed enumerations: a denial reason and an address
class. The egress capability appears in exactly one HTTP response and is stored only as a SHA-256; it is absent
from the command document and its digest, from container labels, from the outbox and inbox, and from logs.

## Residual risks

- **Azure instance metadata at `168.63.129.16` is global unicast** and is not refused by the classifier.
  Special-casing one provider's address would invite the belief that the list is complete; deployments there
  need a network-level control.
- **The attestation is unsigned** and hand-authored by an operator, now with five more controls to get right.
- **The proxy's service credential is in its container environment**, visible to anything with daemon access —
  already the highest privilege on a runner host.
- **Revocation is bounded, not immediate.**
- **One `A` query over TCP is not DNSSEC.** It removes off-path spoofing and nothing more.

## How ADR-025's eight requirements were met

Stated in full in [ADR-025](../adr/025-execution-egress-remains-deny-all.md); resolved by
[ADR-026](../adr/026-enforceable-assignment-scoped-execution-egress.md). Two were reworded because they were
technically incorrect, and both rewordings preserve the property.

| # | Requirement | How it is met |
|---|---|---|
| 1 | No second route | The sandbox is on one `--internal` network whose only other member is the proxy. Asserted as *reachability* — the route to a non-forwarding gateway exists, and ADR-025's "no default route" wording was **corrected** |
| 2 | Resolve and connect in one step | One `A` query per connection; the connection is made to an `InetAddress` built from the classified bytes, which performs no lookup. ADR-025 implied an alternating hostname must always be refused; under a one-resolution algorithm that would require detecting an answer never queried, so the wording was **corrected** to the invariant the implementation proves |
| 3 | Non-global addresses refused after resolution | An allowlist of one class, on raw bytes, with IPv4-in-IPv6 decoded. IPv6 egress refused explicitly |
| 4 | Redirects | **Not followed at all.** The proxy returns the `3xx` untouched; the client's next request is authorized from scratch, and that is the enforcement point |
| 5 | Assignment-scoped credentials | An `EGRESS` capability bound to one authorization, revalidated on every request and every tunnel revalidation, dead the moment the assignment is fenced |
| 6 | Lifecycle tied to the sandbox | Proxy started and proven ready before the sandbox exists; if it cannot start, no sandbox is created. Reconciler covers sandboxes, proxies, and networks |
| 7 | Failure semantics | A proxy that will not start is an infrastructure failure with `testOutcome = NOT_AVAILABLE`; a dead proxy leaves nothing at the other end, which is topological rather than reactive |
| 8 | Image provenance | Built from a Gradle-assembled repository context, identified by full digest, base pinned to a multi-architecture digest, and declared as a test input in all three consuming modules |

## What this still costs

Ordinary Karate suites still cannot run — because there is still no Karate, not because of egress. Admitting
tenant code remains blocked on the stronger runtime [ADR-022](../adr/022-hostile-execution-boundary-and-synthetic-probe.md)
names, with its gate re-run against that runtime as the acceptance criterion. This slice does not revisit that
and does not satisfy it.

A tenant selects a policy; an operator authors one. Self-service authorship is the next product step, and it is
recorded as a limitation rather than described as if it existed.
