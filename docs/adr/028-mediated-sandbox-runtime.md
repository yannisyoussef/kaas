# ADR-028: Run the sandbox under a mediating runtime, with no path back to the baseline

**Status: ACCEPTED.** Extends ADR-022's boundary rather than closing it. Schema
`kaas.sandbox-security-attestation.v4` is required; v3 is refused as v2 was.

## Context

ADR-022 hardened a container sandbox and proved each control with a trusted probe, and then declined to
approve the result for hostile tenant code. Its reason was structural and has not changed: a `runc` container
shares the host kernel, so the reachable attack surface is every syscall the kernel implements, and a kernel
bug is an escape rather than a containment failure. Every slice since has been building the machinery that
would make hostile code safe to run *except* the boundary itself.

ADR-027 signed the evidence. A signature makes an attestation trustworthy; it does not make the boundary it
describes any stronger. A perfectly signed document saying a shared-kernel container enforced sixteen controls
is an authentic statement about a boundary ADR-022 still refuses.

This ADR changes the boundary.

## What was measured, and what that changed

The candidate evaluation is `docs/architecture/hostile-runtime-evaluation.md`. Three of its findings are load
bearing here, and two of them contradicted assumptions this repository had already written down.

**Firecracker is deferred, not rejected.** It requires KVM, which neither the development host nor
GitHub-hosted runners provide, and adopting it would mean a kernel image, a rootfs, a guest agent, tap-device
lifecycle and microVM reconciliation — a different execution model rather than a stronger launcher. gVisor
keeps the OCI workflow, the image model, the profile, the proxy topology and the reconciler, so it is a
launcher change plus a runtime registration.

**A pinned digest is not a working runtime.** The release measured locally, `release-20240729`, refused
*every* network mode on a GitHub-hosted runner — including `--network=none` — with `cannot run with network
enabled in root network namespace`. This document previously attributed that error to nested Docker. That was
wrong, and CI disproved it: the runtime was two years older than the kernel it was asked to run on. The pin is
therefore a **measurement**, re-pinning means re-measuring, and the digest is recorded from two independent
hosts.

**Allowlist egress works under the mediating runtime.** `none`, the default bridge, and a user-defined
`--internal` network all carry a sandbox under the pinned release. This was the evaluation's largest open
item, and it is answered by measurement rather than assumption.

## Decision

### The runtime is a closed set of two, with no ordering between them

`ExecutionRuntimeType` has two constants and no `preferred`, no `fallbackTo`, and no comparison operator. A
runtime name is the name of a program the daemon will execute, so if any part of it came from a request, a
tenant, or an editable profile field, then choosing the runtime would be choosing what runs — a larger
privilege than anything else in the system.

**There is no fallback to the baseline. Not a flag, not a catch block, not a development convenience.** A
sandbox authorized for the mediating runtime that ran under the baseline would be an execution whose evidence
describes a boundary it did not have, and the failure would be silent because everything else about the
container would look identical. Where the runtime is unavailable, the sandbox is refused.

### Requested and enforced are separately observable

Two independent observations, from different sides:

- **The launcher's half.** `docker inspect` reports `HostConfig.Runtime`, read back **before the workload
  starts**, so a mismatch never runs anything.
- **The sandbox's half.** `uname -r` inside the sandbox ends in `-gvisor`. A container cannot choose what the
  kernel says about itself.

The second binds the runtime's *identity*, not its emulated version. It was originally the version prefix
`4.4.0`, measured from release-20240729; the pinned release reports `4.19.0-gvisor`, so a version-bound marker
would have silently stopped matching — a mandatory control failing for a reason with nothing to do with the
boundary.

### The mandatory control set is scoped to the runtime

This is the structural change, and it was forced by measurement rather than chosen. The two runtimes do not
make the same controls observable:

| Control | Baseline | Mediating | Why |
| --- | --- | --- | --- |
| `NO_NEW_PRIVILEGES` | mandatory | **not observable** | gVisor's guest emits no `NoNewPrivs` line at all |
| `HOST_KERNEL_SYSCALL_MEDIATION` | unsupported | **mandatory** | the property the runtime exists to provide |
| `NO_SETUID_BINARIES` | mandatory | mandatory | reads identically under both |

`controlsByProfileVersion` in `packages/api-contracts/mandatory-sandbox-controls.json` is the single source of
truth, and exact equality still applies **within** each set, in both directions. An assessment produced under
one profile can never satisfy the other.

Two things about `NO_NEW_PRIVILEGES` are stated plainly rather than smoothed over. The control is still
applied — it is in the OCI spec either way — and what is missing is any way to observe it from inside. It is
reported `UNSUPPORTED`, never `PASS`, because recording a request as though it were an observation is what
makes a gate decorative. **The mediating runtime therefore carries one fewer demonstrable mandatory control
than the baseline.** `NO_SETUID_BINARIES` was added to cover the escalation path it closes: it observes
directly that there is nothing in the sandbox for a privilege transition to act on, and it reads the same
under every runtime.

A behavioural probe was tried first and rejected on evidence. busybox drops privileges for its own applets
regardless of the target uid, so a setuid binary built from it reports "did not elevate" whether or not the
control is enforced — a mandatory check passing because the binary is broken. The negative control caught it.
Supplying a real setuid binary would have meant a compiler stage pulling unpinned packages into the one image
whose entire job is to be trustworthy.

### The evidence names the boundary twice, and must agree with itself

`sandboxRuntime` is signed alongside `securityProfileVersion`, which already implies it. The redundancy is the
point: two statements about one boundary, both under one signature, so a document that contradicts itself is
refused rather than resolved in favour of whichever field a reader consulted. `RUNTIME_MISMATCH` is a distinct
verification outcome from `PROFILE_MISMATCH` — "describes another boundary" and "contradicts itself" send an
operator to different places.

Three things must name the same runtime: the signed attestation, the command the control plane issues from it,
and the runtime the worker instantiates. The third link can only be checked in the worker, because no other
component knows what that process is configured to launch. It is compared **by name**; nothing resolves the
string to a runtime.

### What the runtime costs, stated as findings

- **Two additional character devices.** `/dev/fuse` and `/dev/net/tun`, both sentry-emulated rather than host
  devices. The allowance is scoped to the runtime; under the baseline those same names still fail
  `NO_HOST_DEVICES`, because there they mean a real device was passed in.
- **A smaller usable process budget.** The sentry's threads come out of the sandbox's own `pids.max`, so a
  ceiling of 64 leaves roughly 16–21 for the workload — and raising memory from 256m to 512m does not move it,
  which is what rules out the memory cgroup. The per-assessment check is correspondingly weaker; that the
  bound *tracks* the ceiling is proven once, separately, by moving the ceiling and requiring the stopping
  point to follow.
- **Roughly three times the wall clock.** The deadline is scaled with the runtime. This is not a relaxation of
  `WALL_CLOCK_TIMEOUT`, which asserts the launcher enforces its own deadline whatever that deadline is. Left
  unscaled, the memory probe was killed at 30.2 seconds before it could report the ceiling that had already
  bounded it — a resource-limit control reporting a timeout, which is the gate measuring the wrong thing.
- **`/sys/firmware` exists and is unmasked.** It is an empty synthetic directory. The masking control now
  judges what a path *exposes* rather than how it was hidden: masked, absent, or present-and-empty all pass;
  present, unmounted and non-empty — the `systempaths=unconfined` case — still fails.

## Consequences

The evidence for the stronger boundary exists in exactly one place: the mandatory `strong-runtime-gate` CI
job. The runtime cannot be installed into Docker Desktop's VM, so **a green local build proves nothing about
it.** The job has no `if:`, no `continue-on-error` and no filter, because a required check is satisfied by
success, skipped *or* neutral — and a suite that skipped itself when the runtime was absent would be
indistinguishable from one that proved a workload was confined by it. The suite asserts the daemon has the
runtime before running anything, so "not installed" is a red gate rather than an empty one.

Required checks become eight.

## Residual risks

- **The sentry is a userspace process on the host kernel, itself seccomp-confined.** This is not a virtual
  machine and this ADR will not describe it as one. A sentry compromise is host-adjacent rather than
  impossible.
- **One fewer demonstrable mandatory control**, as above.
- **Performance is measured on two hosts, neither of them production**, and is not a capacity statement.

## What this does not change

**ADR-022 remains open.** A stronger runtime starting is not the same as hostile tenant content being safe to
run, and this ADR does not close it. Tenant execution remains unavailable: no feature source, no tenant
secrets, no production secret provider, and the only thing the sandbox runs is still a probe this repository
wrote. What changed is the boundary that probe runs behind — and the evidence that says which one it was.
