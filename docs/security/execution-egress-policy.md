# Execution egress policy

**Status: DENY_ALL is the only enforceable policy. ALLOWLIST is refused, not degraded. No egress proxy exists.**

This document states what is true today, why the obvious weaker design is unsafe, and what has to be built
before an allowlist can honestly be offered. The decision itself is
[ADR-025](../adr/025-execution-egress-remains-deny-all.md).

## What is enforced today

A sandbox runs with its network disabled at the container runtime. Nothing inside it can reach anything: not
the public internet, not the platform's own services, not the host, not the cloud metadata endpoint. This is
enforced by the runtime rather than by the workload cooperating, and the hostile-execution probe asserts it
from inside the sandbox on every gate run.

`ALLOWLIST` exists in the domain model, in the database vocabulary, and in the command contract — and is
**refused** at two independent points:

| Where | Refusal |
|---|---|
| control plane, at authorization | `NETWORK_POLICY_NOT_ENFORCEABLE` |
| runner, on validating the command | "cannot enforce the network policy ALLOWLIST" |

The second refusal lives in a module that structurally cannot call the first. Neither can be removed without a
named test going red.

## Why refuse rather than approximate

A run that appeared to have egress control which nothing was applying would be worse than a run with no egress
at all, because somebody would rely on it. A tenant reading "allowlist: api.example.com" in a run profile has
been told a security property holds. If it does not, the platform has lied in the most damaging possible place.

## The design that must not be built

> Validate the requested URL against the tenant's allowlist in application code, then let the sandbox make the
> request normally.

This is unsafe for two independent reasons, either of which is sufficient.

**The check and the connection are separated.** The hostname resolved during validation is not necessarily the
address connected to afterwards. A DNS record with a zero TTL that alternates between a public address and
`169.254.169.254` passes validation on every lookup and connects wherever it likes. This is DNS rebinding, it
is trivial to perform, and it is invisible in logs that record hostnames.

**The sandbox keeps a normal route.** Even with a perfect check, nothing forces traffic through it. Untrusted
content that opens its own socket bypasses the entire mechanism. A control that the controlled party can
decline to use is a suggestion.

## The shape that can work

```
   ┌──────────────────────────────────┐
   │  sandbox                         │
   │  no default route                │        ┌───────────────────────────┐
   │  cannot resolve DNS              │        │  proxy                    │
   │                                  │        │  resolves DNS itself      │
   │   only reachable peer ───────────┼───────▶│  enforces host:port       │
   │                                  │        │  rejects private/reserved │
   └──────────────────────────────────┘        │  re-checks every redirect │
        internal network (--internal)          │  assignment-scoped auth   │
        no gateway, no route out               └─────────────┬─────────────┘
                                                             │
                                                    egress network
                                                             │
                                                             ▼
                                                          targets
```

The sandbox cannot route around the proxy because **there is no other route**. That is the property the whole
design rests on, and it is a property of the network topology rather than of anything the workload agrees to.

## Requirements before ALLOWLIST can be enabled

Summarised here; stated in full with the test each one demands in
[ADR-025](../adr/025-execution-egress-remains-deny-all.md).

1. **No second route.** With the proxy stopped, every outbound attempt must fail.
2. **The proxy resolves and connects in one step.** Defeats DNS rebinding, which is the specific attack.
3. **Private, reserved, and link-local addresses refused after resolution** — including `169.254.0.0/16` and
   therefore cloud metadata, and including IPv4-mapped IPv6 forms. IPv6 handled explicitly or disabled.
4. **Every redirect hop re-checked**, bounded, or not followed at all.
5. **Proxy credentials scoped to one assignment**, revalidated per request, dead the moment it is fenced.
6. **Proxy lifecycle tied to the sandbox's**, including every failure path, with reconciler coverage.
7. **Failure semantics defined first**: a proxy that will not start is an infrastructure failure, not a test
   failure; a proxy that dies must not restore connectivity.
8. **Proxy image repository-controlled and digest-pinned**, and declared as a Gradle test input.

## What this costs today

Ordinary Karate suites cannot run, because most of them need to reach a target API. That is the accepted
consequence rather than an oversight. The alternative — shipping an allowlist that does not hold — would let
those suites run while telling their owners something untrue about the boundary they were running inside.
