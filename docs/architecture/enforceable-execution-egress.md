# Enforceable execution egress

**Status: IMPLEMENTED and VALIDATED for trusted synthetic execution.** `DENY_ALL` and `ALLOWLIST` are both
enforceable. No Karate runtime exists, no `FeatureRevision` source enters a sandbox, and no tenant secret is
delivered anywhere.

## The shape of it

```
                       ExecutionAuthorization
                    (run, attempt, epoch, worker,
                     network policy revision)
                                |
                                v
                        EgressCapability
                 (opaque, short-lived, hash stored,
                  absent from the command digest)
                                |
                                v
                             Runner
                                |
        +-----------------------+-----------------------+
        |                       |                       |
        v                       v                       v
  execution-internal      trusted proxy             sandbox
      network                  |                        |
   (per execution,             +--> assignment validation|
    --internal,                +--> destination policy   |
    verified with              +--> DNS resolution       |
    the daemon)                +--> address classification
        ^                      +--> connect to that exact address
        |                            |                   |
        +----------------------------+                   |
                     the ONLY reachable peer <-----------+
                                                          X
                                                    no direct egress
```

The sandbox is attached to exactly one network. The proxy is attached to that one **and** to the networks that
reach targets, DNS, and the control plane. That asymmetry is the enforcement: nothing in the sandbox is asked
to cooperate, no environment variable is trusted, and a workload that opens a raw socket at an address it was
deliberately told reaches nothing.

## Two policies, two paths

| | `DENY_ALL` | `ALLOWLIST` |
|---|---|---|
| Sandbox network | none | one per-execution `--internal` network |
| Proxy | none | started and proven ready **before** the sandbox exists |
| Egress capability | not issued | issued, rotated per delivery |
| Deployment evidence required | mandatory sandbox controls | those **plus** five egress controls |
| If the egress subsystem is unhealthy | unaffected | refused |

`DENY_ALL` keeps the simpler proven network-disabled path deliberately. A run that wants no network must not
acquire a dependency on the egress subsystem, and issuing it a bearer credential it has nothing to present to
would put a live token into an environment for no reason.

## The order of operations is the security property

For every proxied request and every `CONNECT`:

1. **Read the request strictly.** Anything with two readings is refused rather than resolved — bare LF,
   obsolete folding, duplicate `Host`, duplicate `Content-Length`, `Transfer-Encoding`, whitespace before a
   colon.
2. **Reconcile the destination.** The request line and the `Host` header both state it; a disagreement is
   refused rather than decided in favour of either.
3. **Ask the control plane**, with the capability, whether this destination is permitted *now*. No cache, no
   memo, no "last known good".
4. **Resolve once** — one `A` query, over TCP, no cache object anywhere — and classify **every** answer.
5. **Connect to one of those exact `InetAddress` objects**, built from the bytes that were classified.

Nothing is skipped because an earlier step was expensive, because every one of these can stop being true
between two requests.

## The execution lifecycle

An `ALLOWLIST` run takes this path through the production `ExecutionLoop`, and no other:

```
authorize -> validate command -> PROVISIONING -> RUNNING
                                                    |
                              +---------------------+
                              | create internal network, verify --internal WITH THE DAEMON
                              | start proxy on it, attach it to the egress networks
                              | wait for the proxy to report itself serving
                              | ONLY THEN create the sandbox, on that network and nothing else
                              | run the synthetic egress workload
                              | remove the sandbox
                              | remove the proxy, THEN the network
                              +---------------------+
                                                    |
                              COLLECTING_RESULTS -> PROCESSING_RESULTS -> COMPLETED
```

**If the proxy cannot start, the sandbox is never created.** There is no degraded mode: an allowlist execution
without a proxy is an execution with no enforcement, so the outcome is an infrastructure failure rather than a
run that quietly had no egress control.

**If the proxy dies while the workload runs, the sandbox does not regain connectivity** — its only route was
that container. The runner's health check exists to *classify* the execution truthfully, never to contain it;
containment is topological and holds whether or not anything is watching. The classification is conservative
and unconditional: a workload result gathered while the execution's enforcement point was going away is not
evidence about anything, and reporting it as a test outcome would blame a tenant for the platform.

**Teardown order is load-bearing.** A network with an endpoint attached cannot be removed, so the proxy goes
first; the other order silently reclaims nothing while reporting success.

**The profile is checked, not assumed.** An allowlist execution runs under the *networked derivative* of the
profile its command was authorized under — `kaas.sandbox.v1-internal`, derived from `kaas.sandbox.v1` rather
than restated so the controls cannot drift — and the loop refuses if what the launcher holds is not that
derivative. The version differs because an attestation gathered against a sandbox with no network must not
vouch for one that has a peer.

## What the workload does, and what it does not

The workload is `KAAS_SYNTHETIC_V1`, extended with three egress scenarios. It is a *workload*, not a probe:
it reports the fixed identity and a workload outcome, so an allowlist run completes through the ordinary
lifecycle with `infrastructureOutcome=SUCCEEDED` and `testOutcome=PASSED` rather than being a measurement
taken outside one.

| Scenario | Passes when |
|---|---|
| `egress_allowed` | the proxy authorized and connected — a status line came back and the proxy's own denial header did not |
| `egress_denied` | a destination the policy does not name is refused with `DESTINATION_NOT_ALLOWED` |
| `egress_no_bypass` | no public, private, metadata, or daemon address is reachable directly, and no name resolves |

The first two are only evidence together with the third: a fully routed sandbox satisfies the first, and a
sandbox with no network at all satisfies the third.

**A deliberate denial is successful security evidence, not a failed test.** There is no tenant test here, and
reporting a correct refusal as a failure would teach a reader exactly the wrong thing.

What the workload does *not* do: read `FeatureRevision` source, redeem a secret, choose a destination, or
choose anything else. The destination arrives in an environment variable the trusted launcher sets from the
execution's own policy; the port used to demonstrate a refusal is computed from the policy's own entries, so
it is refused by construction rather than by hope.

## What each component may not do

| Component | Cannot |
|---|---|
| `apps/api` | hold a Docker client; the build fails if it acquires one |
| `services/egress-proxy` | hold a Docker client, the control plane, Spring, or Karate; the build fails |
| `services/runner` | hold the control plane, Karate, an object store, or a secret provider |
| the proxy at runtime | terminate TLS, follow a redirect, speak SOCKS or UDP, read a config file, expose an admin surface |
| a caller, anywhere | choose the proxy image, the network, or any proxy setting |

## Where the clocks live

| Question | Answered by |
|---|---|
| Has this capability expired? | the database, on every revalidation |
| Is this assignment still live? | the database, under the run's row lock, clock read **after** the lock |
| Should this tunnel be re-checked yet? | the proxy's own monotonic elapsed time |

The proxy never compares its own wall clock to a database-generated expiry. It measures an interval, not a
deadline, so a host clock adjustment cannot extend or shorten anyone's authority.

## Revocation bound

```
assignment fenced at T  ->  tunnel unusable by T + revalidationInterval + authorizationTimeout
```

Polling, and documented as polling. A tunnel carries no further HTTP requests once established, so without this
timer nothing would ever re-check the authority behind it.

## What is proven, and where

| Property | Proven by |
|---|---|
| A proxied request reaches an authorized target, and a raw socket at the same address does not | `EgressTopologyBoundaryTests`, real containers |
| One resolution per connection, and the classified address is the one connected to | `EgressDnsSecurityTests` and `EgressProxyProtocolTests`, counted at a real DNS server |
| An established tunnel stops within the documented bound after fencing | `EgressAuthorizationTests` |
| A crashed runner leaves no sandbox, proxy, or network | `EgressReconciliationTests` |
| This host can instantiate the mechanism at all | `EgressEnforcementGateTests` |
| The production path creates the network, starts the proxy, and starts no sandbox when it cannot | `EgressExecutionLifecycleTests` |
| Nobody can name an image, a network, or a proxy setting | `ExecutionEgressBoundaryTests` |
| The control plane's decision is one the proxy's own client understands | `SyntheticExecutionPipelineTests`, real HTTP against the real control plane |
| An allowlist run completes end to end, through a real proxy, as `SUCCEEDED`/`PASSED` | `SyntheticExecutionPipelineTests`, real database, real control plane, real containers |
| A proxy that dies mid-run is an infrastructure failure and never a failed test | `SyntheticExecutionPipelineTests` |
| Canonicalization agrees on both sides | `EgressDestinationTest` and `CanonicalDestinationTest`, two independent implementations of one written contract |

## Where this sits

| Boundary | Established by | This slice |
|---|---|---|
| Who owns an attempt | ADR-021 | reads it, never changes it |
| Whether a sandbox confines what it runs | ADR-022 | consumes its verdict |
| Whether this assignment may execute | ADR-023 | reads it |
| Whether anything executes | ADR-024 | reads it |
| Whether an execution may reach a destination | **ADR-026** | **establishes it** |
| Whether tenant code may run | not decided; ADR-022 names the prerequisite | **deliberately absent** |
