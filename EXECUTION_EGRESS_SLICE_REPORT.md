# Execution egress slice report

## 1. Executive summary

A KaaS execution can now reach the network, and the reaching is enforced rather than asked for. `ALLOWLIST`
is no longer a policy the platform refuses: a run sealed with one executes inside a per-execution `--internal`
Docker network whose only other member is a trusted, repository-owned proxy. The sandbox has no second route
because no second route exists. It is not a URL check followed by ordinary container networking, and it is not
`iptables` configured inside the untrusted container — both of those were forbidden, and both would have been
easier.

The proxy authorizes every request against authoritative control-plane state, resolves each destination
exactly once against a controlled resolver, classifies every returned address, and connects to that exact
address object. An established `CONNECT` tunnel is revalidated on a timer and closed within a **documented
polling bound** after its assignment is fenced — stated as a polling bound because that is what it is.

`DENY_ALL` did not change and keeps the simpler proven network-disabled path. `ALLOWLIST` is still **refused,
never downgraded**, in two independent places: the control plane refuses unless the deployment's own sandbox
security assessment carries five egress controls passing, and the runner refuses unless *this process, on this
host, right now* can instantiate the mechanism — established by building the image, creating a network,
starting a proxy, putting a sandbox on it, and observing, not by reading a flag.

**The single most important finding of this slice was produced by wiring the runner in.** The control-plane
egress endpoint authorized only runs in `CLAIMED`. An executing run is in `PROVISIONING` or `RUNNING` and
never in `CLAIMED`, so the endpoint denied every request a real execution would ever have made. Every unit and
integration test passed, the proxy was correct, the topology was correct, and the mechanism was unreachable.
Nothing found it because nothing had run an allowlist execution end to end. §33 and §34 cover it.

Verification: **backend 407, hostile-execution 116, execution egress 35, synthetic pipeline 33, web 1 —
0 failures, 0 errors, 0 skips.** 14 security mutations run in this pass, 14 killed, on top of the 37 the
earlier stages of the slice recorded. Six real defects fixed: three security-relevant, two fail-closed
correctness defects that made the mechanism unusable rather than unsafe, and one test-quality defect.

## 2. Starting commit

`b716073` — "docs: record the CI stabilization result". Confirmed with `git status --short --branch` and
`git log --oneline --decorate -15` before any work began: the tree was clean, `b716073` was `HEAD`, and all
six GitHub Actions jobs were green on it.

## 3. Scope

Delivered: the allowlist policy model and its migration; a per-execution internal network; a purpose-built
trusted egress proxy in its own module; DNS resolution, address classification, and exact-address connection
inside that proxy; an assignment-scoped `EGRESS` capability; a narrow control-plane validation endpoint with
its own authority; active tunnel fencing with a measured bound; runner integration through the real execution
lifecycle; orphan reconciliation of proxies and networks; a mandatory CI gate; ADR-026; three documents
reconciled; and this report.

Explicitly out of scope by direction, and honoured: **no Karate**, no FeatureRevision source execution, no
production secret redemption, no object storage, no SSE, no quality gates, no arbitrary TCP egress, no
unrestricted internet.

Out of scope by judgement and flagged rather than silently omitted: tenant self-service authorship of a policy
(an operator writes one, a tenant selects it), IPv6 egress (§20), and a signed attestation (§42).

## 4. ADR-025 baseline

ADR-025 stated eight requirements for enforceable egress. Seven are met as written. One was **factually
wrong about Docker** and is corrected rather than preserved.

| # | Requirement | Status |
|---|---|---|
| 1 | No proxy bypass | Met, and its wording corrected. See below |
| 2 | DNS check-to-connect safety | Met; the invariant is restated as what the implementation proves (§17) |
| 3 | Resolved-address filtering | Met, and strengthened from a blocklist to an allowlist of one class (§19) |
| 4 | Redirect handling | Met; the enforcement point is documented truthfully (§22) |
| 5 | Assignment-scoped authorization | Met (§13, §14) |
| 6 | Proxy lifecycle | Met (§23) |
| 7 | Failure/reconciliation semantics | Met (§24, §26) |
| 8 | Repository-owned digest-pinned image | Met (§27) |

**Requirement 1 said "no default route". That is a false description of a true property.** Measured on a
Docker `--internal` network with the existing network probe:

```
net_default_routes=1        <-- Docker --internal DOES install a default route
net_dns=unresolvable
net_global_addresses=1, net_interfaces_up=1
public / private / metadata / link_local / metadata_v6 / docker_host / gateway: ALL unreachable
```

Docker installs a default route to a gateway that cannot forward externally. The security property is
**unreachability**, not the absence of a route entry. ADR-025 and `docs/security/execution-egress-policy.md`
now assert reachability, and the test asserts `net_default_routes == 1` deliberately — because that is
Docker's behaviour rather than ours, and a Docker change here would be a silent channel.

ADR-025 is marked **partially superseded** by ADR-026 rather than rewritten. It remains the historical record
of the eight requirements, and the correction is recorded as a correction.

## 5. Threat model

The sandbox is untrusted. It is assumed to ignore `HTTP_PROXY`, `HTTPS_PROXY`, and every JVM proxy setting; to
open raw sockets at whatever address it likes; to read anything in its own memory, environment, and
filesystem; and to lie in its output. Every control below therefore holds without the sandbox's cooperation.

| Threat | Control | Where it lives |
|---|---|---|
| Direct socket to an authorized target | The sandbox is on one `--internal` network with no route to the target network | Topology |
| Reaching the control plane, database, broker, or daemon | Same: none of them is on the execution network | Topology |
| Reaching another tenant's sandbox or proxy | One network **per execution**, never shared | Topology |
| SSRF to loopback, RFC1918, link-local, metadata | Classification after resolution, allowlist of one address class | Proxy |
| DNS rebinding | One resolution per connection; connect to the exact classified `InetAddress` | Proxy |
| Redirect escape | The proxy never follows a redirect; the client's second request is authorized afresh | Proxy |
| Using a capability after cancellation | Authoritative revalidation on every request and every tunnel poll | Control plane |
| Using a capability from another run, attempt, epoch, or worker | Capability scope, checked against live state | Control plane |
| A tunnel outliving its authority | Timed revalidation, tunnel closed within a documented bound | Proxy |
| Control plane unreachable | Refuse new, close existing | Proxy |
| A stolen proxy service credential | A distinct authority that can ask one question and drive nothing | Control plane |
| A proxy outliving its execution | Runner teardown, plus a reconciler that removes proxies and networks | Runner |
| Tenant topology leaking into shared telemetry | Closed-enum metric dimensions only; no resolved address in shared logs | All three |

## 6. Proxy alternatives reviewed

Three were evaluated against the six properties the prompt requires provable.

**A — a purpose-built narrow KaaS forward proxy.** **B — Envoy or Squid plus a KaaS authorization control
plane.** **C — something else narrowly justified.**

The discriminators were *exact resolved-address connection* and *active tunnel fencing*.

- **Check == connect.** Squid and Envoy resolve internally. Proving that the address which was security-checked
  is the address connected to, through their configuration, is a configuration property rather than a code
  property — and configuration properties fail silently.
- **Active tunnel fencing.** Envoy's `ext_authz` is per-request; an established `CONNECT` tunnel is not
  re-authorized per byte. Squid has no native concept of it. Both would have needed a custom extension anyway,
  so the "proven existing component" argument buys less than it appears to.
- **Surface.** The prompt forbids the proxy exposing generic SOCKS, arbitrary port forwarding, or a broad
  configuration surface. Envoy and Squid are large surfaces where security is config-driven.
- **Image.** The image must be repository-controlled, minimal, non-root, and digest-pinned.

**Selected: A, with a mature DNS library.** Small enough to audit, with no configuration surface to get wrong,
and with both discriminating properties expressed as code that a test can mutate.

## 7. Selected proxy architecture

`:services:egress-proxy` — a separate Gradle module, which is a separate trust domain. Its build **fails** if
it acquires `docker-java` (a proxy that could reach a daemon turns proxy compromise into container escape),
the control plane, Spring, or Karate.

Dependencies: `dnsjava 3.6.3`, `slf4j 2.0.16`, `jackson 3.1.5`. Nothing else (§27).

Protocol surface: forward-proxied HTTP and `CONNECT`, and nothing else. No SOCKS, no UDP associate, no generic
relay. **One request per connection**, `Connection: close` upstream, so every request is authorized separately.
`Transfer-Encoding` is refused outright. The response is never parsed, so the proxy cannot disagree with the
target about framing. Redirects are not followed.

## 8. Trust boundaries

```
UNTRUSTED                    TRUSTED                        AUTHORITATIVE
┌──────────┐   only peer   ┌─────────────┐   one question  ┌───────────────┐
│ sandbox  │──────────────▶│ egress proxy│────────────────▶│ control plane │
└──────────┘               └─────────────┘                 └───────────────┘
     │                            │                                │
     └── no route anywhere else   ├── no Docker daemon             └── no destination resolution
                                  ├── no control-plane code
                                  └── no host filesystem, no mounts
```

The proxy is trusted in the sense that it may reach networks the sandbox may not. It is not trusted to be
unconstrained: non-root, read-only root filesystem, all capabilities dropped, no-new-privileges, no mounts, no
daemon socket, and bounded memory, CPU, processes, and logs (§27).

The control plane deliberately does **not** resolve the destination. Doing so would put a gap between the
address that was checked and the address that gets connected to, which is the rebinding hole the design exists
to close.

## 9. Network topology

```
  host                       bridge          kaas-egress-*           kaas-exec-<execution>
  ┌────────────┐                │            (internal)              (internal)
  │ control    │◀───────────────┤                 │                        │
  │ plane, DNS │◀───────────────┤                 │                        │
  └────────────┘                └──── proxy ──────┴────────────────────────┤
                                                                           │
                                target ───────────┘              sandbox ──┘
```

**The proxy is on more networks than the sandbox. That asymmetry is the no-bypass property**, and it is
topological, so nothing in the sandbox's configuration is asked to cooperate.

One network **per execution**, never shared. A shared egress network would put every tenant's sandbox on one
broadcast domain, where reaching a neighbour needs no route out at all — only a neighbour.

`ExecutionNetwork.create` sets `--internal` and then **verifies the flag with the daemon**, destroying the
network if it is not set. A network that exists under the expected name with the wrong flag would attach
successfully and route freely while every label said otherwise. `SandboxSecurityProfile` accepts exactly two
network shapes: `none`, or a name prefixed `kaas-exec-`. `host`, `bridge`, and every other name are refused by
that rule rather than by a denylist, because a denylist of dangerous networks stops being complete.

## 10. No-bypass evidence

Release-blocking, and measured against real containers.

| Claim | Evidence |
|---|---|
| A proxied request to an allowed target succeeds | `200` **plus the target's own sentinel body** — a `200` alone could have come from the proxy |
| A raw socket at the same address the sandbox was told fails | `EgressTopologyBoundaryTests`, with the target address handed to the sandbox deliberately |
| Public, private, metadata, and daemon addresses are unreachable | Enumerated, not asked about one named target |
| The sandbox cannot resolve names for itself | `net_dns=unresolvable`, asserted every run |
| With the proxy stopped, nothing is reachable and no route appears in its place | Proxy **stopped, not removed**, so its endpoints survive and the only change is that nothing is listening |
| The sandbox is on exactly one network, and it is the internal one | Asserted against the daemon |

**Neither half of the pair means anything alone.** A sandbox with no network at all satisfies the negative; one
on a routed network satisfies the positive. They are asserted together, in the same topology, in the same run.

## 11. ALLOWLIST policy model

Deliberately narrow. A destination is **exact host + explicit port + scheme**, and nothing else is
representable: no wildcards, no `*.example.com`, no tenant-chosen CIDR, no `0.0.0.0/0`, no raw IP literals, no
UDP, no host network, no arbitrary Docker network names, no arbitrary proxy configuration.

IP-literal entries are **refused in v1**. An exact FQDN with an explicit port is enough for the first product
capability, and an IP literal skips the resolution step the whole design is built around.

`NetworkPolicyRevision` carries the destination list. A `DENY_ALL` must have none — a `DENY_ALL` carrying
destinations would be a policy whose name and content disagree, invisibly to anything reading only the type.
An `ALLOWLIST` must have at least one — an empty allowlist permits nothing, which is `DENY_ALL` under a name
suggesting otherwise.

`permits()` is **byte equality on the canonical form over the whole list**. Never a suffix test, never a prefix
test, never a wildcard expansion: those are the operations that make an allowlist match something its author
did not write.

## 12. Hostname canonicalization

`packages/api-contracts/egress-allowlist-canonicalization.md` is **normative and written down**, implemented
independently by the control plane and by the proxy. They agree by both implementing one written rule, not by
sharing a class — the proxy must carry no runtime dependency on control-plane code, and a shared library would
make a change in one silently a change in the other. A contract test proves they still agree.

**Nothing is silently rewritten.** A host that is not already canonical is refused with a reason. Accepting
`EXAMPLE.com.` and storing `example.com` would mean the destination a tenant wrote and the destination the
platform enforces are two different strings, and the tenant would have no way to see the difference.

Refused: upper case, trailing dot, empty labels, whitespace, percent-encoding, userinfo, brackets, unicode,
leading or trailing hyphen labels, wildcards, CIDR, IP literals, and single-label hosts (which removes
`localhost`, and which would otherwise resolve through per-host search domains — so the same entry would mean
different destinations on different machines). Lower-casing is checked with `Locale.ROOT`, because in a Turkish
locale the default mapping sends `I` to a dotless `i` and the same entry would canonicalize differently
depending on the JVM's locale.

The runner has a **third, deliberately narrower** check (`EgressTarget`). It is not a third implementation of
the grammar: it permits exactly the contract's character set, so it can never reject a destination the control
plane accepted, and it exists because the runner puts the value into a container environment and into a
request line the probe writes — whitespace, a control character, a CR or LF, userinfo, or a percent escape is a
refusal there regardless of what any policy says.

## 13. Assignment-scoped egress capability

An `EGRESS` capability under the existing kaas-11 capability architecture rather than a new bearer-token
system. Scoped to organization, project, run, attempt, assignment epoch, worker, network policy revision and
digest, and an expiry bounded by the authorization's own.

**The plaintext is never persisted.** Only `token_sha256`, the scope, and the windows are stored. The token
exists in one HTTP response and nowhere else: not in the command document, not in the database, not in a log,
a metric, a container label, RabbitMQ, the outbox, or the inbox. It is delivered into the sandbox's
environment, which is the one place it has to be, and the working assumption is that whatever runs there can
read it — so its protection is not secrecy from the workload but the narrowness of what it authorizes.

`V12` adds `ck_execution_capabilities_egress_is_not_redeemed`. An egress validation **delivers nothing**, so it
must not consume the 64-redemption amplification ceiling: it runs on every request and every tunnel
revalidation, hundreds of times per execution, and wiring it into the redemption path would fence a healthy
execution within seconds for reasons nobody could reconstruct.

Rotation revokes the previous capability in the same transaction that mints its replacement, so ten worker
retries leave one working token rather than ten.

## 14. Proxy authorization

`POST /internal/v1/egress/authorizations`. The proxy presents the capability, the canonical host, the port, and
the scheme. The control plane revalidates, under the run's row lock with the clock read under it: token hash,
capability type, capability and authorization windows, run lifecycle, attempt state, assignment epoch, worker
identity, lease expiry, policy digest against policy content, and destination membership.

**Always `200`, whatever the verdict.** A status-coded denial is an oracle: a holder of one capability could
learn from the status alone whether another exists, whether a run is live, or whether a destination is in some
policy. The body carries a verdict and a category and nothing else — no run identifier, no policy contents, no
worker identity, no expiry instant, no resolved address.

**A distinct authority.** The subject `kaas.egress-proxy` is granted `ROLE_KAAS_EGRESS` and only that; every
other `kaas.*` subject gets `ROLE_KAAS_SERVICE` and only that. The proxy sits on a network an untrusted sandbox
can reach, which makes its credential the most likely one in the system to be taken — and a stolen one can ask
this single question and cannot advance a phase, submit a result, or mint an authorization for any run in any
tenant. This is **not mTLS**, which does not exist in this deployment, and nothing here claims otherwise.

## 15. Active tunnel fencing

The failure this exists to prevent: authorize at `CONNECT`, then relay for as long as the workload likes. No
further HTTP request crosses an established tunnel, so without a timer nothing would ever cause the authority
to be checked again, and an assignment fenced a second after `CONNECT` would leave a working channel open
indefinitely.

**Mechanism: periodic authoritative revalidation.** `maximumRevocationLatency = revalidationInterval +
authorizationTimeout`, derived from the two values the proxy is actually configured with rather than written
down separately, so the documented number cannot drift from the real one.

It is a **polling bound and is documented as one.** Revocation here is not immediate, and describing it as
immediate would be a security claim the implementation cannot support.

The interval is monotonic and local; the absolute expiry question belongs to the control plane, so the proxy
never compares its own wall clock against a database-generated expiry (§28).

Measured: an assignment fenced mid-tunnel cut the tunnel within the documented bound. The test asserts the
tunnel was **cut rather than run to its own end**, and counts the revalidation requests that arrived after
`CONNECT` — so the timer is proven to have run rather than assumed.

## 16. DNS architecture

The **proxy** resolves. The sandbox does not, and cannot: it is on an internal network with no reachable
resolver, asserted every run.

`dnsjava` against a configured server, over **TCP not UDP** — a UDP answer is trivially forgeable by anything
on the path, and the path here includes an entire container network. TCP is not authentication, but it removes
off-path spoofing, and one lookup per connection makes the cost irrelevant.

**A records only.** v1 carries IPv4, so an AAAA answer could never be connected to. Querying for one anyway
would mean either ignoring the result or refusing destinations that merely publish IPv6 — which would make
every dual-stack destination unusable. An AAAA-only destination fails with `DNS_FAILED`, which is honest and
fail-closed, and a dual-stack destination works over its A record.

**No cache object anywhere in the path**, so "one resolution per connection" is a property of the code rather
than a hope about JVM-global cache state. It is measured **at the DNS server**, not inferred from application
mocks.

**Multiple answers: if any returned address is outside global unicast, the whole resolution is refused** — not
just that address. Skipping the bad one and connecting to a good one would work, and would also mean a name
resolving partly into private space is treated as an ordinary external destination. A mixed public/private
answer set is the shape of a rebinding attempt, and the conservative reading of an ambiguous answer is to
refuse it.

## 17. DNS rebinding analysis

ADR-025 requirement 2 implied that a hostname alternating between a public and a private answer must **always**
be refused. Under a correct one-resolution algorithm that would require detecting an answer that was never
queried. The wording is corrected to the invariant the implementation actually proves:

> **The address that is security-checked is the address that is connected to.**

That is stronger in the way that matters and weaker only in the way that was never true. Under one resolution
per connection there is no second DNS answer "between validation and connection", because there is no second
lookup.

Proven with a **real DNS server that changes its answers between queries**, not a resolver mock:

- a permitted name answering `10.0.0.7` → `403 ADDRESS_NOT_GLOBAL` (policy said yes, the address said no — the
  only shape that reaches the classifier at all);
- a permitted name answering `169.254.169.254` → refused, as a consequence of the address model;
- alternating answers across requests → `200 / 403 / 200`: each connection classified afresh, neither
  inheriting a previous verdict nor condemned by one;
- exactly **one A query per proxied request**, counted at the server that answered it;
- a refused destination is **never resolved**, so the proxy is not a DNS oracle.

## 18. Exact-address connection

```java
socket.connect(new InetSocketAddress(address, port), connectTimeout);
```

`address` is an `InetAddress` built from the bytes that were classified. `InetSocketAddress(InetAddress, int)`
performs no lookup. There is no API in the path that takes a hostname.

**The most important mutation in this slice** replaced that with a connection by hostname. It **survived at
first**: both paths fail to connect in a test environment where the name does not resolve locally, so the
outcome was identical and the test that claimed to catch it did not. It was fixed with
`-Djdk.net.hosts.file`: the name gets a **global** answer from the controlled DNS server and a **loopback**
answer from the JDK, the test listens on loopback, and asserts **zero arrivals**. Now decisive.

## 19. Address classification

Filtering happens **after** resolution. Safety is never decided from the hostname.

The classifier is an **allowlist of one class — global unicast — not a blocklist**. A blocklist of special-use
ranges is a list that stops being complete; the reserved-range registry changes and the code does not.

It classifies **raw bytes, never a parsed `InetAddress`**. The JDK collapses `::ffff:8.8.8.8` to four bytes,
and that is a JDK property rather than a wire fact.

Refused as a consequence of the rule rather than as named cases: `0.0.0.0/8`, loopback, RFC1918, link-local
(which is what refuses `169.254.169.254`), CGNAT `100.64.0.0/10`, multicast, broadcast, documentation ranges,
benchmark ranges, and the unspecified address. Named regression tests are kept for cloud metadata anyway,
because of impact.

## 20. IPv6 decision

**Option B: IPv6 egress is explicitly refused in v1**, and it fails visibly rather than silently.

But **embedded IPv4 is decoded and classified anyway** — mapped, compatible, 6to4, and Teredo — so
`::ffff:127.0.0.1` reports `LOOPBACK` rather than `IPV6_NOT_SUPPORTED`. The reason is that otherwise that code
would be written for the first time on the day IPv6 is switched on, which is the worst possible day to write
it.

Tested: AAAA-only destinations, dual-stack destinations, IPv4-mapped IPv6, unique-local, link-local, and `::1`.

`IMPLEMENTATION_STATUS.md` records IPv6 egress as **REFUSED**, and the standing instruction not to enable it
until the classifier covers it and is tested against it is written down.

## 21. Metadata and private-network defense

`169.254.169.254` is refused because it is link-local, not because it is AWS. The generic rule makes metadata
denial a consequence of the address model rather than a special case, and named regression tests exist anyway.

**Residual risk, stated rather than papered over:** Azure's instance metadata endpoint at `168.63.129.16` is
global unicast and is therefore **not** refused by the address classifier. Special-casing one provider's
address would invite the belief that the list is complete. Deployments on that platform need a network-level
control.

## 22. Redirect semantics

The proxy **does not follow redirects on anyone's behalf, and does not inspect the `Location` header.**
Claiming otherwise would document a control that does not exist.

The `3xx` is handed back untouched. If the client follows it, that produces a **new proxied request**, and the
enforcement point is that request being authorized afresh. Measured: allowed host returns `302`, the synthetic
client follows it, and the second request is `403 DESTINATION_NOT_ALLOWED`.

Also tested: redirects to a private IP literal, to a hostname resolving private, and to a port not in the
policy.

## 23. Proxy lifecycle

For an `ALLOWLIST` execution, in the production `ExecutionLoop`:

1. authorization revalidated, command independently validated, digest recomputed;
2. `PROVISIONING` announced, then `RUNNING`;
3. the internal execution network created and **verified internal with the daemon**;
4. the proxy started on it, attached to the deployment's egress networks;
5. **waited for readiness** — from the proxy's own log line, because the launcher sits on the host and cannot
   reach an internal network, which is precisely the property being relied on elsewhere;
6. only then the sandbox created, attached to the execution network **and nothing else**;
7. the synthetic egress workload run;
8. the sandbox terminated and removed;
9. the proxy removed;
10. the network removed.

**Teardown order is load-bearing.** A network with an endpoint attached cannot be removed, so removing the
proxy first is what makes removing the network possible rather than silently reclaiming nothing.

Every error path cleans up. A failure anywhere past network creation removes everything already created,
because a half-started egress is a gateway with no execution behind it — the one artefact this whole mechanism
exists to make impossible.

**The sandbox profile is checked, not assumed.** An allowlist execution runs under the *networked derivative*
of the profile its command was authorized under, and the loop refuses if what the launcher holds is not that
derivative. Without it, a launcher configured against some other profile would run the execution anyway and the
evidence would name a policy that did not produce it. The version string differs (`kaas.sandbox.v1-internal`)
because an attestation gathered against an airgapped sandbox must not vouch for a networked one; it is
*derived* from the base profile rather than restated, so the controls cannot drift.

## 24. Proxy crash semantics

**If the proxy cannot start, no sandbox is created.** There is no degraded mode: an allowlist execution
without a proxy is an execution with no enforcement.

`infrastructureOutcome = FAILED`, `testOutcome` not invented. In this repository that is expressed as the run
moving to `STOPPING` with `stop_reason = INFRASTRUCTURE_FAILURE` and the attempt's
`infrastructure_disposition = FAILED`, with `test_outcome` left `NULL` — nothing ran, so there is nothing to
report about a test, which is the difference between "not available" and "failed". The terminal settle is the
reconciler's job, not the worker's.

**If the proxy dies while `RUNNING`, the sandbox does not regain connectivity.** That is structural: the
sandbox's only route was the proxy container, so when it stops, connections fail because there is nothing
there — not because something noticed. The runner's detection exists to **classify** the execution truthfully,
never to contain it.

The classification is **conservative and unconditional**: if the proxy is not running when the sandbox
finishes, the execution is an infrastructure failure regardless of what the workload reported. The workload
will have reported `FAILED` — it could not reach anything — and a platform that read that as the answer would
tell a tenant their test failed when the platform's own gateway had died underneath it. Nothing has been
submitted at that point, so nothing trustworthy is discarded.

The failure category travels; the cause does not. A daemon error carries socket paths, host directories, and
image references.

## 25. Cancellation and lease loss

Both fence the assignment, and fencing is what makes an unexpired capability stop working.

- **New requests** are refused at the next authorization, which is every request.
- **Existing tunnels** are closed at the next revalidation, within the documented bound (§15).
- The sandbox is terminated, the proxy removed, the network removed. **No surviving gateway.**

Cancellation moves the run to `STOPPING` *and* fences the assignment, so two independent checks would refuse
the egress. That would leave the lifecycle check jointly covered and provable by neither — so the test asserts
the **reason**: the lifecycle check runs first and answers `RUN_NOT_EXECUTING`, which pins that specific check
rather than the pair.

Lease loss is proven separately, with the capability's own TTL untouched: expiry bounds the damage from a leak,
and authoritative revalidation is what makes fencing effective.

## 26. Orphan reconciliation

The existing sandbox reconciler now manages sandboxes, proxies, and per-execution networks.

**Containers first, then networks.** A network with an endpoint cannot be removed, so the other order silently
reclaims nothing and reports success — the reconciler would appear to work while accumulating exactly the
resource it exists to reclaim.

Networks are removed only if **managed-labelled AND empty AND older than the abandonment window**. Never
`docker network prune`, which would take a developer's compose network, another tool's network, and anything
created a moment ago by a process this one cannot see.

Attachment is read with `inspect`, not from the list response: listing does not populate the attached-container
map on every daemon version, and treating an unpopulated map as "empty" would mean deleting live networks.

Proven: a crashed runner leaves no sandbox, no proxy, and no network; a live execution's proxy and network are
left alone whoever owns them; an unrelated network is never removed however unused it looks; an abandoned
network that still has something attached is left alone; a newly created network is not reclaimed while it is
still empty; and the managed-label filter is asserted **not vacuous**.

Labels carry identity for reconciliation and nothing else — never a bearer token, a tenant secret, a resolved
target IP, or a target credential. A label is readable by anything that can list containers and outlives the
process.

## 27. Proxy image and supply chain

`services/egress-proxy/src/main/docker/Dockerfile`. `FROM eclipse-temurin@sha256:3137541d…`
(25.0.4_7-jre-alpine-3.24), **verified to be a multi-arch OCI index** so the same digest resolves on arm64
development machines and amd64 CI. Pinning a platform manifest would build on one and fail on the other, and
the usual repair for that is to un-pin.

The build context is assembled by a Gradle `Sync` task — the Dockerfile, the jar it just built, and exactly its
runtime classpath (7 jars) — published as a consumable configuration and **resolved** by the consuming modules
rather than reached across to. Reaching into another project's build directory works right up until it silently
does not: the test can run before that project has produced anything, and the evidence is then an image built
from whatever a previous build left behind.

Smoke-verified: it refuses to start with no configuration, runs as uid 65534, and starts with `--read-only`
and no writable mount at all (`-XX:-UsePerfData` is what removes the JVM's need for a writable temp directory).

**The image identity is resolved to a full digest.** Carrying forward the kaas-10 lesson: the build reports a
twelve-character abbreviation, which is content-derived but **is not a digest**. `EgressProxyProfile` refuses a
tag, a short id, and a 64-character non-hex string; the three legitimate spellings of a content address are
accepted, so the rule is not simply always red. `EgressCapability` carries the digest the **gate** built rather
than rebuilding it, so the evidence and the executions describe the same artifact.

Dependency review:

| Dependency | Version | License | Native code | Why |
|---|---|---|---|---|
| dnsjava | 3.6.3 | BSD-2-Clause | None | Explicit control over server, transport, timeout, and the full answer set. The prompt's instruction not to hand-roll a DNS parser |
| slf4j-api + slf4j-simple | 2.0.16 | MIT | None | The proxy is a standalone process with no framework to provide a binding |
| jackson-core/databind/annotations | 3.1.5 | Apache-2.0 | None | One small JSON request and response |

No service mesh, no HTTP client library, no TLS library. The transitive footprint is those artifacts and
nothing else, asserted by the module's own dependency guard.

## 28. Clock authority

Both lessons carried forward.

**Persisted server-generated timestamps** still use `PersistableClock` from the stabilization pass. Not
regressed; the backend suite that proves it still passes.

**Authorization windows** are evaluated against `repository.currentDatabaseTime()`, read **under the run's row
lock**. Reading the clock before taking the lock was a real defect on the source path: every window check was
evaluated against an instant from before the wait on a contended row, and a capability was demonstrably served
after it had expired.

**Local intervals are monotonic.** The proxy's revalidation interval and connection timeouts use elapsed time.
The proxy never compares its own wall clock against a database-generated expiry, which is what would fail under
ordinary host or VM clock skew. The distinction between *absolute authorization expiry* (the control plane's)
and *local monotonic revalidation interval* (the proxy's) is maintained in both directions.

## 29. Persistence

`V11 — egress allowlist policy`: `network_policy_destinations`, immutable and un-truncatable, with canonical
form `CHECK`s mirroring the written contract, an index, and a **restatement of the seeded `DENY_ALL` digest**.

*Why the seeded row is restated.* Covering destinations **always** — count zero included — changes every
digest. The alternative, a conditional encoding that skipped the component for `DENY_ALL`, would have preserved
the old digest and is exactly the special case that later lets a field go uncovered. One canonical form, one
row restated. The documented consequence: a command issued before `V11` binds the v1 digest and is refused
after it. That is bounded by the five-minute command TTL and fails closed.

`V12 — egress capability`: widens `ck_execution_capabilities_type` to admit `EGRESS`; adds the
non-redemption `CHECK` (§13); gives `network_policy_revisions` nullable `organization_id` / `project_id`
(`NULL` = platform-global = the seeded `DENY_ALL`) with `ALLOWLIST` required to be owned; adds
`projects.network_policy_revision_id` and `run_snapshots.network_policy_revision_id`, both `NOT NULL DEFAULT`
the `DENY_ALL` id — **existing rows keep the posture they had**, which is the one direction a migration must
never move on its own; and a `require_policy_belongs_to_referrer` trigger on **both** tables, because a guard
on one leaves the other as the way in.

`run_snapshots.network_policy_revision_id` is deliberately **not** in `content_sha256`. Adding it to the
preimage would make every run already in a deployment fail its own integrity check permanently. It is protected
by snapshot immutability instead, and the **command** carries the revision and digest for the runner to verify.

Not persisted: bearer token plaintext, resolved target IP history, proxy service credentials.

## 30. Migration evidence

Both permanent rules applied: fresh database → current, and **populated previous version → current**.

**The migration gate found a real hole.** The fixture previously had **zero** `execution_capabilities` rows, so
`V12`'s re-added `CHECK` would have been validated against an empty table — `CREATE OR REPLACE` and a re-added
constraint do not validate rows that are not there, and the test would have passed while proving nothing. An
`executionAuthorityFixture` was added: an authorization, a redeemed `SOURCE`, and an unredeemed `SECRET`, on
the fenced attempt of the stopping run.

Two mutations confirm the gate: `m01` (the `CHECK` silently loses `'SOURCE'`) and `m02` (the egress exemption
forbids redemption for every type) both go red — and **only the populated test goes red**, which is the entire
point of the gate.

## 31. Structured egress result semantics

Two categories that must not be conflated.

**A policy denial is not a test failure.** `EGRESS_DENIED` with a reason — `DESTINATION_NOT_ALLOWED`,
`ADDRESS_NOT_GLOBAL`, `ASSIGNMENT_FENCED`, `CAPABILITY_EXPIRED`, `RUN_NOT_EXECUTING`. For the synthetic
security workload, a **deliberate denial is successful security evidence**: the workload asserts that a
destination outside the policy *is* refused, and reporting a correct refusal as a failure would teach a reader
exactly the wrong thing. There is no tenant test yet, so no future UX is decided here.

**Infrastructure failure is a different fact with a different owner.** `EGRESS_PROXY_BUILD_FAILED`,
`EGRESS_PROXY_START_FAILED`, `EGRESS_PROXY_NOT_READY`, `EGRESS_PROXY_DIED`, `EGRESS_NETWORK_FAILED`,
`EGRESS_CLEANUP_FAILED` — a separate enum from the denial reasons, because a single category covering both
would make the evidence unable to tell them apart.

The distinction between "the proxy could not ask" and "the answer was no" is carried on the wire: `503` for the
former, `403`/`407` for the latter. Evidence that cannot tell those apart cannot diagnose an outage.

Successful synthetic allowlist execution: `infrastructureOutcome = SUCCEEDED`, `testOutcome = PASSED`, zero
tenant features, producer `kaas-runner-synthetic`, and no string `KARATE` anywhere in the document.

## 32. Observability

| Component | Metrics | Dimensions |
|---|---|---|
| Control plane | `kaas.egress.authorization` | `result` (decision enum) |
| Proxy | `kaas_egress_authorization_total`, `kaas_egress_denied_total`, `kaas_egress_address_refused_total`, `kaas_egress_tunnel_revoked_total`, `kaas_egress_active_connections` | `reason` (denial enum), `class` (address class enum) |
| Runner | `kaas_egress_proxy_launch_total`, `kaas_egress_proxy_failure_total`, `kaas_egress_reconciliation_total` | `reason` (failure enum), `resource` (resource kind) |

**Every dimension is a value from a closed enumeration, and the method signatures are what enforce it** — there
is no counter method on any of the three that accepts a `String` label. Never used as a dimension: run id,
attempt id, epoch, hostname, IP, capability id, tenant id.

**Cardinality here is a security property, not only an operational one.** A metrics store is read across
tenants and retained far longer than a log, and a resolved address is the one value this subsystem is most
careful never to emit into a shared surface. A test asserts, against a real execution with a real capability
and a real destination in play, that none of them appears in a series name — asserted against the values
actually in play, so a counter that had picked one up is caught rather than argued about.

Resolved destination addresses do not enter ordinary cross-tenant operational logs. Stable low-cardinality
reason codes are used instead.

## 33. Security review

Ten adversarial passes, one per lens, each asked the prompt's own questions.

| # | Lens | Question | Answer |
|---|---|---|---|
| 1 | Network isolation / Docker topology | Can the sandbox bypass the proxy? | No route exists. Verified with the daemon (`--internal` read back), enumerated from inside the sandbox, and asserted with the proxy both running and stopped |
| 2 | SSRF / DNS / rebinding | Can a checked hostname connect to a different IP? | `socket.connect(new InetSocketAddress(InetAddress, port))` performs no lookup. There is no hostname-taking API in the path. Mutated to connect by name; killed by a decisive test only after the first attempt failed to catch it |
| 3 | HTTP proxy / CONNECT | What destination representation can bypass canonicalization? | One strict parser, refusing rather than repairing. Colon-from-the-right, leading-zero ports, trailing dots, IP literals, single labels, userinfo, and case were each mutated individually — two survived at first because a *different* rule caught them, and were fixed by pinning the refusal **reason**, not the exception type |
| 4 | Distributed authorization / fencing | Can an existing CONNECT tunnel survive assignment fencing? | Not past the documented polling bound, measured. **This lens found the `CLAIMED`-only defect** (§34) |
| 5 | PostgreSQL / capability integrity | Can a capability outlive its basis? | Windows evaluated under the row lock with the clock read under it; rotation revokes in the same transaction; the migration gate validates against populated rows |
| 6 | Runner lifecycle / reconciliation | What resource can the reconciler delete that it does not own? | Only managed-labelled, empty, and abandoned. Never `prune`. Six tests, including two that assert what it must *not* touch |
| 7 | Container security | Can the proxy outlive its sandbox? | Teardown removes proxy then network; a crashed runner is covered by the reconciler; CI fails if any managed container or network survives the job |
| 8 | QE / mutation validity | What test stays green when its claimed protection is removed? | 51 mutations in the ledger, all killed — but **eight needed a test fixed first**, and those are the findings that mattered |
| 9 | CI gate integrity | Can the gate pass without running? | No job-level `if`, no `continue-on-error`, no filters; `cleanEgressSecurityTest` before the run; an executed-test floor; zero skips; named suites; and a leak check |
| 10 | Architecture / threat modelling | What documented property is stronger than the implementation can prove? | Three were found and corrected: ADR-025's "no default route" (§4), "always refuses an alternating hostname" (§17), and "follows and validates redirects" (§22) |

**P0/P1 findings, all fixed:**

1. **P0 — the egress endpoint authorized only `CLAIMED` runs**, so it denied every request a real execution
   would make. §34.
2. **P0 — an in-execution egress refusal returned without telling the control plane.** Three paths (no
   mechanism, no egress material, profile mismatch) returned a rejection *after* `RUNNING` had been announced,
   leaving the run in its phase until a deadline reclaimed it and recorded a timeout — the exact bug the
   infrastructure-failure endpoint exists to end. Now reported.
3. **P1 — `EgressTarget` accepted hosts the runner cannot safely handle.** It checked only case, so a trailing
   dot, embedded whitespace, and userinfo all passed into a container environment and a request line. §12.
4. **P1 — the proxy image reference was built and discarded.** `EgressCapability.establish` returned
   `new EgressCapability(true, null, null)`: the field existed and was always null, so an execution would have
   had to rebuild the image and could have got a different one. Now carried from the gate.
5. **P1 — a duplicated request writer.** The probe's proxied-request helpers existed once inside the `egress`
   mode; the workload mode needed them too. Two copies of a CRLF-correct request writer is two chances to emit
   a bare LF, which is where request smuggling lives. Hoisted to one definition.
6. **P2 — two daemon assertions were global.** "No execution network survives" asked a question the test could
   not own on a shared daemon, and failed for debris a mutation run had deliberately leaked. Scoped to the
   runner generation under test; the global form belongs at job level in CI, where it now is.

## 34. Mutation evidence

**51 mutations in the ledger, all killed. 14 of them were run in this pass** — the ten `k` series and the
four `x` series, whose results the earlier working log did not record — and all 14 were killed. The other
37 are the earlier stages' recorded results, carried forward rather than re-run.

The ledger below records only those where the outcome is worth remembering. **A mutation is not counted as
covered when an unrelated earlier guard happens to reject the same request**, which is why several rows
read "survived first": the test that claimed to catch it did not, and the fix was to the test.

| # | Mutation | Result |
|---|---|---|
| a01–a04 | Address classifier: link-local rule, embedded-IPv4 decode, LOOPBACK permitted, 172.16/12 boundary | RED |
| c01–c03, c06 | Canonicalization: host case, IP-literal refusal, single-label refusal, leading-zero port | RED |
| c04, c05 | Colon-from-the-right; trailing-dot rule | **SURVIVED first.** Both were caught by a *different* rule, so the specific rule was not independently proven. Fixed by pinning the refusal **reason** rather than the exception type |
| p01 | Connect by hostname instead of by classified address | **SURVIVED first.** The most important mutation in the slice; see §18 |
| p02–p07 | Authorization skipped; classification removed; mixed answer skips the bad one; Host/target agreement; credential required; authorize-after-resolve | RED |
| n01 | Execution network not internal | RED (all three topology tests) |
| n02 | Delete the internal-flag check | **SURVIVED first** — unreachable on the happy path. `requireInternal` made package-private and driven against a genuinely non-internal network |
| t01, t02 | Exec network not internal; sandbox also joined to the egress network | RED |
| f01–f03 | Tunnel never revalidated; denial no longer closes the tunnel; unreachable control plane reads as yes | RED |
| g01 | The contract test's own array bound | **SURVIVED first** — without it the mandatory set silently absorbed the egress controls the moment a second array joined the shared JSON |
| g02–g05 | Declared-but-not-wired control; three all-green gate checks indistinguishable from `return true` | **SURVIVED first.** Fixed by splitting the **verdict** from the act of **obtaining the evidence**, so red paths can be driven with evidence a healthy host never produces |
| m01, m02 | Migration `CHECK` silently loses `SOURCE`; egress exemption forbids redemption for every type | RED — and **only the populated test goes red**, which is the point of the gate |
| e01 | Attestation digest comparison | **SURVIVED first** — it compared two attestations built at two `Instant.now()` calls and would have passed with `egressControls` left out of the preimage entirely |
| r01–r05 | Reconciler: proxy filter, network filter, age scope, ownership, emptiness | RED |
| x01–x04 | Capability minted for `DENY_ALL`; destination check removed; lease-expiry check removed; snapshot policy pinning removed | RED |
| **k01** | The allowlist branch runs the configured workload instead of the egress one | RED — an execution that completes perfectly having demonstrated nothing. Caught by the DNS query count, not by the outcome |
| **k02** | The loop never takes the egress branch | RED (3 tests) |
| **k03** | Proxy death during a run is no longer noticed | RED — the run completes as `FAILED`, blaming the tenant for the platform's dead gateway |
| **k04** | The lifecycle rule reverts to `== CLAIMED` | RED — this is the defect the slice found (§33) |
| **k05** | A `STOPPING` run may produce egress | RED — killed by the test that asserts the **reason**, not merely the refusal |
| **k06** | A failed egress start leaves its network and proxy behind | RED |
| **k07** | The denial port becomes one the policy names | RED — the workload would assert the opposite of what it claims, and pass |
| **k08** | The plan prints its credential | RED |
| **k09** | The control plane stops delivering destinations | RED (3 tests) |
| **k10** | The runner stops refusing unsafe hosts | RED (4 cases) |

**Joint coverage, recorded rather than claimed as independent:** removing the loop's profile-derivation check
is jointly covered with `DockerSandboxLauncher`'s own profile-version refusal — the launcher throws where the
loop would have returned, so both must be present but neither can be killed alone. It is reported as a joint
pair.

**Anti-vacuity:** every structural rule has a positive case beside its negative one — a per-execution network is
accepted, the three legitimate spellings of a content address are accepted, an isolated sandbox passes the
no-direct-route control, and a live capability authorizes before the thing under test takes it away.

## 35. QE evidence

| CI job | Task | Tests | Failures | Errors | Skips |
|---|---|---|---|---|---|
| `backend` | `check` minus the three excluded test tasks | **407** | 0 | 0 | 0 |
| — of which `apps:api` | `:apps:api:test` | 291 | 0 | 0 | 0 |
| — of which `services:egress-proxy` | `:services:egress-proxy:test` | 116 | 0 | 0 | 0 |
| `hostile-execution-gate` | `:services:runner:test` | **116** | 0 | 0 | 0 |
| `execution-egress-gate` | `:services:runner:egressSecurityTest` | **35** | 0 | 0 | 0 |
| `synthetic-execution-pipeline` | `:tests:pipeline:test` | **33** | 0 | 0 | 0 |
| `web` | `npm test` | 1 | 0 | 0 | 0 |
| `contracts` | schema + fixture validation, OpenAPI lint | 12 schemas, 33 fixtures | 0 | 0 | 0 |

592 tests in total, 0 failures, 0 errors, 0 skips. The egress gate's floor is 30 executed tests against 35
actual, so adding a test does not break the gate while deleting a suite does.

**The success path could not be proven in-process, and that is documented in the test class itself.** The
classifier permits global unicast only, and a test JVM can bind loopback or an RFC1918 host address — neither
of which is global. Weakening the classifier so local tests can reach an RFC1918 listener is explicitly
forbidden, and would delete the control from every test claiming to exercise it. The success path, tunnel
fencing, and no-bypass are therefore proven in the **Docker topology suite**, where containers hold addresses
the production classifier accepts: an `--internal` network on `11.0.0.0/24`, which is allocated and globally
unique and therefore global unicast to the production classifier, but not routed on the public internet — and
the network's `--internal` flag is asserted, so the synthetic "global" address space provably has no route off
the host.

**Three probe bugs were found by running it**, each of which had made a control look broken or look fine:

1. the probe sent a bare LF, so every request came back `400`. The parser was right; the probe was wrong.
2. the tunnel probe wrote to a path on the read-only root, so the tunnel never opened at all.
3. the tunnel probe timed a **pipeline** containing `sleep N`, so it always reported `N` — a promptly-cut tunnel
   was indistinguishable from one never cut. Then: busybox `nc` does not exit when the far end closes while its
   own stdin is open. Fixed by giving `nc` a FIFO stdin and **writing a byte a second**: the failing write is
   the measurement. The revocation had been working the whole time; the probe could not see it.

**A harness bug was found and fixed:** the mutation runner counted only `<failure>` and not `<error>`, so a
mutation that made a test *throw* was scored as a survivor. Re-checked: no earlier verdict changed.

## 36. Gradle test-input evidence

Every task that reads external security material declares it, so a file-only change cannot leave a security
test `UP-TO-DATE`.

| Task | Declared inputs |
|---|---|
| `:services:runner:test`, `:services:runner:egressSecurityTest` | the probe and egress-target build contexts; the shared mandatory-control contract; **the resolved proxy image context** (Dockerfile, proxy jar, and every jar on its runtime classpath) |
| `:tests:pipeline:test` | the probe build context; the migrations; the egress-target context; the resolved proxy image context |

The proxy image context is obtained **through dependency resolution**, published as a consumable configuration
by `:services:egress-proxy` and resolved by its consumers — so Gradle builds it first and treats its contents
as a task input. Reaching across into another project's build directory works right up until it silently does
not (§27). The context path is passed to the tests as a system property derived from the resolved
configuration; a test that guessed the path would build whatever a previous build left there.

Mutation-checked: editing the proxy's Dockerfile or a line of proxy code makes these tasks re-run rather than
report `UP-TO-DATE`.

## 37. CI gate evidence

A new **mandatory** job, `execution-egress-gate`, not hidden inside `backend`.

- **No job-level `if`. No `continue-on-error`. No branch or path filter.** A required check is satisfied by
  success, *skipped*, **or** *neutral*, so a job that skipped itself when Docker was absent would be
  indistinguishable from one that proved a sandbox cannot reach the internet.
- **Missing prerequisites fail.** No daemon, no proxy image, no DNS fixture, no evidence: each fails.
- `cleanEgressSecurityTest` runs before the suite, so an `UP-TO-DATE` result cannot satisfy the gate. A cached
  pass would report green without having started a single container.
- **Evidence inspected from the XML:** at least 30 executed tests, **zero skipped**, and six named suites
  present — `EgressTopologyBoundaryTests`, `EgressDnsSecurityTests`, `EgressAuthorizationTests`,
  `EgressReconciliationTests`, `EgressEnforcementGateTests`, `EgressExecutionLifecycleTests`.
- **A leak check:** zero KaaS-managed containers and zero KaaS-managed networks may survive the job. An
  orphaned proxy is a running egress gateway with no execution behind it, still holding a service credential
  and still attached to the target network — leaking one in CI is the same defect as leaking one in production,
  and this is where it is cheap to notice.

The job introduces **no new third-party action**. Existing action SHA-pinning debt is untouched and remains
documented rather than expanded into this slice.

## 38. Files changed

**New — control plane:** `EgressAuthorizationService`, `EgressAuthorizationController`, `EgressDestination`,
`EgressScheme`, `V11__egress_allowlist_policy.sql`, `V12__egress_capability.sql`, `EgressDestinationTest`.

**New — proxy (`:services:egress-proxy`, whole module):** `Scheme`, `CanonicalDestination`,
`MalformedDestination`, `MalformedRequest`, `ProxyRequest`, `RequestReader`, `RequestedTarget`, `AddressClass`,
`AddressPolicy`, `ResolvedTarget`, `ResolutionRefused`, `TargetResolver`, `DenialReason`,
`AuthorizationDecision`, `EgressAuthorizer`, `ControlPlaneAuthorizer`, `ProxyMetrics`, `ProxyConfiguration`,
`Tunnel`, `ProxyServer`, `ProxyMain`, its `Dockerfile`, and its suites.

**New — runner:** `EgressProxy`, `EgressProxyImage`, `EgressProxyProfile`, `EgressProxyStartFailed`,
`EgressFailure`, `ExecutionNetwork`, `EgressCapability`, `EgressEnforcementGate`, **`EgressTarget`,
`EgressPlan`, `EgressDeployment`, `EgressExecution`, `EgressExecutions`, `DockerEgressExecutions`,
`EgressMetrics`**, the egress-target image, and six suites including **`EgressExecutionLifecycleTests`** and
**`ExecutionEgressBoundaryTests`**.

**Modified — control plane:** `ExecutionAuthorizationService` (egress capability, destination delivery),
`ExecutionAuthorizationController`, `ExecutionAuthorizationRepository` and its JDBC implementation,
`NetworkPolicyRevision`, `NetworkPolicyType` (`ALLOWLIST` becomes enforceable), `CapabilityType`,
`SandboxSecurityAttestation` (schema v2), `SecurityConfiguration` (`ROLE_KAAS_EGRESS`), **`RunLifecycle`**,
`JdbcRunIntentRepository`.

**Modified — runner:** `ExecutionLoop` (the allowlist branch), `CommandValidator`, `DockerSandboxLauncher`,
`SandboxSecurityProfile`, `SandboxLabels`, `OrphanSandboxReconciler`, `SyntheticProbe`, `probe.sh`.

**Modified — build and CI:** `settings.gradle.kts`, `services/runner/build.gradle.kts`,
`tests/pipeline/build.gradle.kts`, `.github/workflows/ci.yml`.

**Modified — contracts:** `sandbox-security-attestation.schema.json` and its seven fixtures;
`mandatory-sandbox-controls.json`; new `egress-allowlist-canonicalization.md`.

**Modified — documentation:** ADR-025 (corrected, partially superseded), ADR-026 (new), `docs/adr/README.md`,
`docs/security/execution-egress-policy.md`, `docs/security/threat-model.md`,
`docs/architecture/enforceable-execution-egress.md` (new), `docs/architecture/security-boundaries.md`,
`docs/architecture/observability-contract.md`, `README.md`, `IMPLEMENTATION_STATUS.md`,
`SYNTHETIC_EXECUTION_LIFECYCLE_SLICE_REPORT.md`.

## 39. Verification

```
./gradlew clean check --no-daemon \
  -x :services:runner:test -x :services:runner:egressSecurityTest -x :tests:pipeline:test
./gradlew :services:runner:cleanTest :services:runner:test --no-daemon
./gradlew cleanEgressSecurityTest :services:runner:egressSecurityTest --no-daemon
./gradlew :tests:pipeline:cleanTest :tests:pipeline:test --no-daemon
npm --prefix packages/api-contracts ci && npm --prefix packages/api-contracts run validate:schemas
npm --prefix packages/api-contracts run lint:openapi
npm --prefix apps/web ci && npm --prefix apps/web run lint && npm --prefix apps/web run typecheck
npm --prefix apps/web test && npm --prefix apps/web run build && npm --prefix apps/web audit --omit=dev
docker compose -f infrastructure/local/docker-compose.yml config
git diff --check
docker ps -aq --filter label=kaas.managed=true          # empty
docker network ls -q --filter label=kaas.managed=true   # empty
```

All green. No stale KaaS-managed containers or networks remained.

**One observation recorded rather than dismissed.** On one of three executions of the backend task, 12 of the
291 `apps:api` tests failed. The same task passed 291/291 immediately before and 291/291 immediately after, on
the same tree. The failures fell into three clusters, none of which touches anything this slice changed:
`WorkerAssignment.acquiredBy` rejecting an acquisition as earlier than its own claim, three RabbitMQ consumer
tests receiving no message, and one outbox guard refusing a transition. All three are container- and
timing-dependent, and the failing run began immediately after a `clean` while four Docker-heavy suites and a
mutation battery had been running against the same daemon.

That is a plausible explanation, not a diagnosis, and it should not be read as one. What can be said is that
the failure is not reproducible on this tree, and that the assignment-acquisition cluster is the same *class*
of host-clock-ordering problem the kaas-12a stabilization pass fixed for a different symptom — which makes it
worth a look on its own rather than worth attributing to load and forgetting. It is flagged here because a
one-in-three local failure that nobody writes down is a CI flake somebody else debugs later.

## 40. GitHub Actions verification

**Run [33965580342](https://github.com/yannisyoussef/kaas/actions/runs/33965580342), commit `70e1ea1`
("docs: record the enforceable execution egress slice"), conclusion `success`.**

| Job | Conclusion | Duration |
|---|---|---|
| `backend` | **success** | 6m 12s |
| `hostile-execution-gate` | **success** | 4m 20s |
| `synthetic-execution-pipeline` | **success** | 2m 29s |
| `execution-egress-gate` | **success** | 2m 46s |
| `web` | **success** | 30s |
| `contracts` | **success** | 12s |
| `infrastructure` | **success** | 6s |

Seven of seven green, on the two commits of this slice: `3069cb2` (the mechanism) and `70e1ea1` (the
documentation).

**Evidence counts, read from the jobs' own inspection steps rather than from the build summary:**

```
execution-egress-gate        executed=35   skipped=0
                             containers=0  networks=0
hostile-execution-gate       executed=116  skipped=0
synthetic-execution-pipeline executed=33   skipped=0
```

These are identical to the local numbers in §35 and §39, which is the point of recording both: a gate whose CI
count differs from its local count is a gate running something other than what was verified.

The egress gate's floor is 30 against 35 actual, and it ran `cleanEgressSecurityTest` first — so the 35 were
executed on that machine in that run rather than satisfied by an `UP-TO-DATE` result. Zero KaaS-managed
containers and zero KaaS-managed networks survived the job, checked on the runner itself.

**The one apps/api flake described in §39 did not reproduce.** The `backend` job ran the same 407 tests on a
clean Linux runner and passed, which is consistent with that failure being local contention rather than a
property of the tree — but it is one data point, and the observation in §39 stands as written.

## 41. Required-check governance

Seven intended required checks after this slice:

```
backend    hostile-execution-gate    synthetic-execution-pipeline    execution-egress-gate
web        contracts                 infrastructure
```

`execution-egress-gate` is new. Its name must stay **globally unique across every workflow in this repository**,
because required-check resolution matches on job name alone.

**This report does not claim these are enforced.** Branch-protection state is administration configuration that
this work has no permission to read or modify. What is asserted is that the jobs exist, all seven ran and
passed on run 33965580342 (§40), are mandatory in the sense that nothing in the workflow can make them skip,
and are named exactly as above. Whether GitHub *requires* them before a merge is a setting somebody has to
check in the repository's own configuration.

## 42. Residual risks

1. **Azure instance metadata at `168.63.129.16` is global unicast** and is therefore not refused by the address
   classifier. Special-casing one provider's address would invite the belief that the list is complete.
   Deployments on that platform need a network-level control.
2. **The attestation is unsigned and deployment-scoped**, while the property it attests is host-scoped. It
   detects a partially edited document rather than authenticating one, and no producer serializes it — an
   operator hand-authors it, now with five more controls to get right. A signed attestation with a pinned key
   remains the named next step, and it matters more than it did.
3. **The proxy's service credential is visible to anything with daemon access**, because it is in the
   container's environment. Daemon access is already the highest privilege on a runner host, so this is not a
   new boundary, but it is not nothing.
4. **Revocation is bounded, not immediate.** A fenced assignment's tunnel remains usable for up to one
   revalidation interval plus one authorization timeout.
5. **The gate proves the mechanism can be instantiated, not that the proxy honours a decision.** Assessing the
   latter would require the gate to hold a control plane, which the module boundary forbids; it is proven by
   the test suites and the pipeline instead, which is the right place for a property of the code rather than of
   the machine.
6. **One A query per connection is not DNSSEC.** TCP removes off-path spoofing and nothing more.
7. **Policies are platform-authored.** A tenant selects; an operator creates. That is a real product limitation
   and the next step.
8. **Docker remains a shared-kernel boundary.** Nothing in this slice changes ADR-022's prerequisite, and the
   proxy being trusted does not make the sandbox's boundary stronger.

## 43. Exact blockers before Karate

Enforceable egress is no longer one of them. What remains:

1. **The ADR-022 runtime prerequisite** — a stronger boundary than shared-kernel Docker (gVisor or a microVM),
   with the hostile-execution gate re-run against it as the acceptance criterion. Tenant code is not approved
   until this is met.
2. **A worker that heartbeats during execution over a long run.** The lease currently has to outlive the whole
   run, and the 30-minute execution budget exceeds it.
3. **A production `SecretValueProvider`.** The only provider refuses, so a secret-bearing run stops at
   authorization.
4. **`FeatureRevision` source redemption into a sandbox**, which is architecturally prevented today and proven
   so by tests that must not be deleted to enable it.
5. **A signed sandbox security attestation** (§42.2), which matters more now that five more controls depend on
   an operator getting a hand-authored document right.
6. **A tenant-facing egress policy surface**, so that an allowlist is something a tenant can author rather than
   request.

## 44. Recommended next slice

**The signed sandbox security attestation, with a producer.** It is the smallest piece of work that removes the
largest remaining lie in the evidence chain: five egress controls and every mandatory sandbox control now rest
on a document an operator writes by hand, digested by a rule that detects editing but authenticates nothing. A
signed attestation with a pinned key, produced by the gate rather than transcribed from it, turns the whole
security-gate story from "someone asserted this" into "this deployment demonstrated it and can prove it did".

It is also a prerequisite that only gets more expensive: every future control added to the gate adds another
field an operator can get wrong, and the incentive to widen `attestation-max-age` grows with each one.
