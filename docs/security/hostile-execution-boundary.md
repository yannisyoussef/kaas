# Hostile-execution boundary

**Status: IMPLEMENTED FOR A TRUSTED SYNTHETIC PROBE.** The sandbox boundary exists, is enforced, and is proven from inside by a repository-controlled probe. No user content has been anywhere near it, and a passing assessment does not change that.

## Two kinds of fencing, and why both are needed

**State fencing** — the database refuses a stale worker's writes. A worker whose assignment was fenced,
cancelled, or whose lease expired cannot advance a phase, submit a result, or redeem a capability. This has
been true since ADR-021 and is unchanged.

**Execution fencing** — the runner stops a stale worker's computation. Added by
[ADR-029](../adr/029-continuous-execution-authority.md).

They are different claims and neither implies the other:

```
a fenced worker whose writes are all rejected
        is still burning CPU inside a sandbox
        until something stops the sandbox
```

For a workload this repository wrote, that gap is wasteful. For hostile tenant code the compute *is* the
problem, so a test proving stale results are rejected proves nothing about it.

What execution fencing guarantees: a sandbox is terminated within roughly one heartbeat interval plus one
graceful stop window of a definitive revocation, and within the remaining lease budget when the control plane
cannot be reached at all. **No sandbox continues indefinitely after its execution authority has ended.**

What it does not guarantee: instantaneous termination, or cleanup after a hard crash — a `SIGKILL`ed runner
still relies on the lease expiring and the orphan reconciler removing what is left.


## What this is, and what it is not

```
trusted launcher (holds daemon access)
        |
        v
fixed, versioned security profile
        |
        v
trusted synthetic probe  <-- the ONLY executable content
        |
        v
    isolated sandbox
        |
        +--> no network at all
        +--> non-root, no capabilities
        +--> read-only root
        +--> bounded memory / PIDs / CPU / wall time / output
        +--> no secrets
        +--> no user source
        |
        v
security assessment
        |
        v
    PASS / FAIL
```

And then, in large letters, the thing this document exists to say:

> **PASS ≠ feature execution enabled.**

A passing assessment means one prerequisite is met. The ones it does not touch — source capability issuance, secret capability issuance, an egress policy model — are precisely the mechanisms that would let user content near the sandbox in the first place. `kaas.execution.enabled` is not a toggle waiting to be flipped; there is no code behind it, and the application refuses to start if it is set.

## Why a synthetic probe first

Karate feature files execute JavaScript and make network calls. They are hostile executable content and always have been. The tempting order of work is to build execution and then secure it; the order here is the reverse, because a boundary that has never been tested against anything is indistinguishable from a boundary that does not work.

So the only thing this sandbox will run is a shell script that lives in this repository, goes through the same review as everything else, and does nothing but report what it can observe about its own confinement. Every check is an observation or a bounded, self-limiting attempt whose *failure* is the evidence. Nothing here attempts to exploit a host: a probe capable of damaging a host would be a worse thing to run than the untrusted content it exists to make safe.

## The trust boundary

The distinction the whole design rests on:

| | trusted launcher | untrusted sandbox |
|---|---|---|
| daemon access | yes | **never** |
| chooses container settings | yes | **never** |
| holds credentials | the daemon connection | none |
| runs | repository code | the synthetic probe |

A container holding the Docker socket is a root shell on the host wearing a container's clothes. That is why the socket is never mounted, why the launcher lives in `services/runner` rather than in the API, and why the control plane's build fails if it ever acquires a container-runtime dependency. The process that handles tenant requests must not be able to talk to a daemon at all.

The launcher's input is three fields — a probe from a server-side enumeration, a profile version, and a correlation id. None is a container setting. Making a dangerous configuration *unrepresentable* is stronger than validating it away, because validation is something you can forget to call and a type is not.

## Why standard Docker, and what it does not solve

The candidates were standard Docker hardening, rootless Docker, Docker with user namespaces, gVisor, and Firecracker-style microVMs.

**Standard Docker with maximal hardening is the MVP boundary**, chosen on one criterion: every control in the mandatory set can be turned on *and then demonstrated from inside the sandbox* on every host KaaS runs on, with no special daemon configuration. A control that only some hosts can enforce cannot be part of a baseline, and a control that cannot be demonstrated is a claim rather than a boundary.

What it does **not** mitigate, stated plainly:

- **Container escape via a kernel vulnerability.** A container shares the host kernel. A kernel bug is an escape, and no amount of capability dropping changes that. This is the reason the boundary is not sufficient for user content and why gVisor or a microVM replaces it before user content is admitted.
- **Side channels.** Shared CPU, cache, and memory bandwidth between concurrent workloads.
- **Daemon compromise.** The launcher's own daemon access is a high-value target; this design confines it to one module rather than eliminating it.
- **Anything the host's kernel does not support.** Seccomp, AppArmor, SELinux, and user namespaces vary by host, which is why they are reported as deployment-specific rather than claimed.

Rootless Docker and user namespaces are genuine improvements available on some hosts and are recorded as deployment hardening. Kubernetes was not selected: it is an orchestration choice, and adopting it for security would be theatre — it does not by itself provide a stronger kernel boundary than the one described here.

## Mandatory controls, and how each is proven

Every one of these is verified *behaviourally* — by what the probe could observe or do — never by reading back the setting that requested it.

| Control | Evidence |
|---|---|
| non-root uid and gid | the process reads its own `id -u` / `id -g` from the kernel |
| no capabilities | `CapEff`, `CapPrm`, `CapBnd`, `CapInh`, `CapAmb` all read as zero from `/proc/self/status` |
| no-new-privileges | `NoNewPrivs: 1` in `/proc/self/status` |
| read-only root | a write to a directory **the sandbox user owns** is refused |
| writable tmpfs | a write to `/tmp` succeeds |
| no daemon socket | no unix socket anywhere on the sandbox's own filesystems |
| no host mounts | the whole mount table is enumerated and every entry falls inside the permitted set |
| no host devices | no block device nodes of any name, and only allowlisted character devices |
| minimal environment | the environment contains only what the profile put there plus shell intrinsics |
| no network | zero global addresses, zero default routes, zero interfaces up — and public, private, cloud-metadata, link-local, IPv6-metadata, docker-host and gateway destinations all unreachable, with DNS unresolvable |
| PID ceiling | the probe asks for more processes than allowed, gets fewer, and never reaches its completion marker |
| memory ceiling | the daemon reports the kernel OOM-killed the sandbox, and the allocation loop never completed |
| wall-clock deadline | a probe that reported it had started sleeping for an hour ran to the deadline, was terminated within tolerance, and was removed |
| bounded output | a flood is truncated and the bytes the collector retained are within the ceiling; the daemon's host-side log is bounded separately |

| kernel paths masked | the runtime's `/proc` overmounts — `kcore`, `keys`, `timer_list`, `scsi`, `/sys/firmware` — are present |

The read-only row is worth dwelling on. The obvious probe — write to `/` — passes whether or not the filesystem is read-only, because user `65534` cannot write there anyway. It proved file permissions and would have kept passing with the control switched off. Mutation testing caught it; the probe now writes to a directory the sandbox user owns, so the mount flag is the only thing that can refuse. That directory is created by a single Dockerfile line, and deleting it restored the original defect *silently*, so the probe also reports the directory's owner and the gate refuses the evidence unless it matches the observed uid.

Two properties of this table matter more than any individual row. Every row is an **enumeration** rather than a question about a named path: asking whether `/host` existed found a mount at `/host` and nothing else, while the daemon socket at `/run/docker.sock`, a home directory at `/mnt/host`, and the host's root disk renamed to `/dev/loop0` all reported clean with the sandbox holding the host. And **absent evidence is a failure**, never a pass: five of these controls once reported success on a sandbox that produced no observations at all, which is what a failed start, an unreachable daemon, or an undrained output stream all look like.

## Two independent gates

A sandbox that provably confines what it runs is **not** permission to run anything in it. That distinction is
the whole reason this document and [ADR-023](../adr/023-execution-authorization-and-assignment-scoped-capabilities.md)
describe separate mechanisms.

Everything above establishes one property: *if something runs here, this is what it can and cannot do.* It says
nothing about whether a particular piece of work is allowed to run at all — whether the run that asked for it
still exists, whether the worker asking still owns it, whether the egress policy it needs can be enforced, or
whether its secrets can be supplied. Those are questions about authority, and a boundary cannot answer them.

Execution therefore requires **both**:

```
approved sandbox boundary        AND        valid assignment-scoped authorization
   (this document)                              (ADR-023)
   "what could it do?"                          "may this run, now, by this worker?"
```

Neither substitutes for the other, and the failure modes of confusing them run in both directions. Treating a
secure sandbox as permission would let any caller with a live lease execute anything, at any time, under any
policy — the sandbox would faithfully confine work nobody authorized. Treating a valid authorization as safety
would run authorized work inside a boundary nothing had demonstrated, which is the state every deployment is in
by default here, and why authorization refuses when no assessment is present.

The link between them is deliberately narrow. The gate produces a structured assessment; the control plane
consumes it as evidence and binds the profile version and assessment digest into the authorization it issues, so
an audit can answer *which boundary was this authorized against*. The control plane cannot call the gate — it is
build-guarded against depending on the module that holds container-runtime access — and the gate knows nothing
about runs, attempts, or authorizations. Each is useless alone and neither can weaken the other.

## Deployment-specific hardening

Reported for operational visibility, never required, and never claimed where it cannot be shown:

- **seccomp** — on by default on most daemons; the probe reports the filter mode from `/proc/self/status`. Mode 2 says a filter is loaded, not *which* filter, and the launcher pins none, so a permissive host profile is indistinguishable from the runtime's default. The evidence line says so.
- **user namespaces** — the probe reports `/proc/self/uid_map`. On an identity mapping the container's uid *is* the host's uid, which is what "non-root" is worth without them.
- **AppArmor / SELinux / rootless daemon** — genuine improvements requiring daemon configuration. These are **not currently reported**: only seccomp and the uid map are. Naming them here as reported when the gate emitted neither was itself an overclaim, and this sentence replaces it.

A host missing these is reported as `UNSUPPORTED`, never as a pass. The gate does not fail on them, because failing on a control the runtime cannot provide would make the mandatory set unachievable and the gate meaningless.

## What is still absent, deliberately

- **No feature source.** `FeatureRevision` content is never materialised, mounted, or delivered.
- **No secrets.** `SecretReference` redemption does not exist; the probe receives no capability of any kind.
- **No ExecutionCommand.** Claiming a run still grants ownership and nothing else.
- **No egress allowlist.** Deny-all is the proven baseline. A destination allowlist is a real product requirement that gets its own policy model — approximating one now would mean claiming egress control that has never been tested. Denying DNS does not solve SSRF, and this slice does not claim it does.
- **No production execution path.** The probe runs through a security harness. `CLAIMED → PROVISIONING` remains unreachable.

## Residual risk

Not everything here is solved, and the honest list is:

1. **Kernel-shared isolation.** The primary residual risk, and the reason this boundary is explicitly not approved for user content.
2. **Launcher daemon access.** Confined, not eliminated.
3. **Host-dependent hardening.** A deployment without seccomp or AppArmor is weaker than one with them, and the gate reports this rather than preventing it.
4. **Image supply chain.** The base is digest-pinned and the probe is built from repository content, but no signing, SBOM, or vulnerability scanning exists yet.
5. **Orphan cleanup depends on a reconciler that nothing schedules.** The deadline lives only inside the launcher process: with the launcher halted, a flooding sandbox kept running and was measured about 16.5 minutes from filling the host filesystem. The log ceiling caps the rate; the reconciler's age rule can reclaim it; nothing invokes that reconciler yet.
6. **CPU quota is configured but its behavioural evidence is weak** — a timing-based CPU assertion would be flaky, so it is asserted as configuration only and is not in the mandatory behavioural set. Separately, the quota bounds the *sandbox*, not the host CPU the sandbox causes: measured at roughly 3.2x the quota, because the daemon's own encoding and the kernel's writeback fall outside the container's cgroup.
7. **No admission control.** Nothing limits how many sandboxes exist at once, so every per-unit-time cost multiplies: four concurrent sandboxes saturated host disk I/O and made an unrelated container's start-and-write round-trip 5.3x slower.
8. **The sandbox has no run, attempt, or epoch identity and no cancellation hook.** Given a fenced attempt there is no query that finds its container, and a worker cannot abort a sandbox it no longer owns.
9. **No dependency integrity pinning.** The Gradle wrapper has a SHA-256 and the base image a digest; the Java dependencies have neither. `docker-java` 3.4.1 also carries roughly 25 known transitive advisories into the only process with daemon access.
10. **No observability.** The module has no logger, no metric, and no health indicator, so an operator cannot see that a sandbox was created, timed out, or failed to be removed.

## Prerequisites before user content may enter

In order, each with its own slice and its own adversarial review:

1. A stronger kernel boundary — gVisor or a microVM — with this same gate re-run against it.
2. Source capability issuance: what a capability is bound to, how long it lives, whether it survives fencing, how a fenced worker's capability is revoked.
3. Secret capability issuance, on the same terms and with a separate threat model.
4. An egress policy model that replaces deny-all with destination-aware control.
5. Output and artifact handling for genuinely untrusted content.
