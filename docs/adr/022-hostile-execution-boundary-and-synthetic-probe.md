# ADR-022: Prove the sandbox boundary with a trusted synthetic probe before any user content exists

- **Status:** IMPLEMENTED (supersedes [ADR-006](006-docker-runner.md))
- **Amended 2026-09-05:** the runtime prerequisite is **SATISFIED FOR THE MEDIATED RUNTIME**, for inert
  tenant-byte delivery only. See "Adjudication" at the end of this document. **The original rationale below
  is unchanged and is still the reason ordinary Docker was refused.**
- **Date:** 2026-08-28
- **Decision owners:** KaaS security architecture
- **Scope:** The hostile-execution trust boundary, a hardened container sandbox, a repository-controlled synthetic security probe, and an executable release gate. No feature source, no secrets, no execution command, no production execution path.

## Context

ADR-006 proposed a Docker runner and refused to promote it, on the grounds that "Docker is not automatically a sufficient hostile-code boundary." That judgement was correct and is preserved here. What it left open was everything that would make the judgement actionable: which controls, enforced how, and proven by what.

KAA-004 has been the open item behind every slice since. Meanwhile the control plane grew a claim, a lease, and a fencing model — all of which stop at ownership precisely because there was no boundary to hand work across.

The tempting order of work is to build execution and then secure it. This ADR takes the reverse order, because a boundary that has never been tested against anything is indistinguishable from a boundary that does not work.

## Decision

### The only thing the sandbox runs is a probe we wrote

Not Karate. Not feature source. Not a user-selected image, entrypoint, or argument. A shell script that lives in this repository, is baked into an image built from this repository, and does nothing but report what it can observe about its own confinement.

Every check is an observation or a bounded, self-limiting attempt whose *failure* is the evidence. None attempts to exploit the host — a probe capable of damaging a host would be a worse thing to run than the untrusted content it exists to make safe.

### Standard hardened Docker is the MVP boundary, and is not sufficient for user content

Candidates were standard Docker hardening, rootless Docker, Docker with user namespaces, gVisor, and Firecracker-style microVMs.

Standard Docker wins on one criterion: every control in the mandatory set can be enabled *and then demonstrated from inside the sandbox* on every host KaaS runs on, without special daemon configuration. A control only some hosts can enforce cannot be a baseline; a control that cannot be demonstrated is a claim rather than a boundary.

It does not mitigate container escape through a kernel vulnerability, because a container shares the host kernel. That is not a gap to be closed by more capability dropping — it is the reason this boundary is explicitly **not approved for user content**, and why gVisor or a microVM must replace it, with this same gate re-run against it, before user content is admitted.

Kubernetes was not selected. It is an orchestration choice and would be security theatre here: it provides no stronger kernel boundary than what is described above.

### The launcher holds daemon access; the sandbox holds none

A container with the Docker socket is a root shell on the host wearing a container's clothes. So the socket is never mounted, the launcher lives in `services/runner`, and the control plane's build fails if it ever acquires a container-runtime dependency. The process that serves tenant requests must not be able to talk to a daemon at all.

The launcher's input is a probe from a server-side enumeration, a profile version, and a correlation id. Nothing a caller supplies is a container setting, so there is no argument that could weaken the policy. Making a dangerous configuration *unrepresentable* beats validating it away: validation is something you can forget to call, and a type is not.

### Controls are split by what can be proven, not by what sounds good

**Mandatory** controls are portable across every host KaaS runs on and are verified behaviourally: non-root, no capabilities, no-new-privileges, read-only root, writable tmpfs, no daemon socket, no host mounts, no host devices, minimal environment, no network, PID ceiling, memory ceiling, wall-clock deadline, bounded output.

**Deployment-specific** hardening is reported but never required, and a host that lacks it is recorded as `UNSUPPORTED` rather than as a pass. Requiring a control the runtime cannot provide would make the gate unachievable; claiming one it cannot enforce would make the gate a lie. Two are reported today: the seccomp filter mode, and the uid map that says whether a user namespace is in effect. AppArmor, SELinux, and rootless daemons are **not** reported — this sentence originally listed all five as reported when the gate emitted one, which is the same class of overclaim the gate exists to prevent.

CPU quota is configured and deliberately kept out of the mandatory *behavioural* set: a timing-based assertion would be flaky, and a flaky security test is worse than an honest gap.

### The gate fails closed, and passing it enables nothing

A mandatory control that cannot be positively demonstrated fails the assessment. There is no "log a warning and continue" path, because a green assessment that tolerates missing controls is worse than no assessment.

And passing it does not enable execution. `kaas.execution.enabled` has no code behind it; the application refuses to start if it is set, and there is deliberately no development bypass — a bypass is the first thing an attacker looks for, and a boundary with a documented way around it is not a boundary.

That claim was false when first written. Nothing injects the guard, so its only enforcement was a `@PostConstruct` callback, and under `spring.main.lazy-initialization=true` the container never instantiated it: the application started cleanly with execution enabled. One documented Spring property was the bypass this paragraph said did not exist. The guard is now `@Lazy(false)`, and the control has tests for the first time — it previously had none of any kind, in the slice's most-cited security claim.

### Deny-all networking, not an allowlist

The sandbox has no network. Not a restricted one — none. It is the strongest simple default and the only one whose enforcement is trivially provable. A destination allowlist is a genuine product requirement and gets its own policy model; approximating one now would mean claiming egress control that has never been tested. Denying DNS does not solve SSRF, and this slice does not claim it does.

### The environment is built from nothing

Never the host's environment with known-sensitive names removed. Subtraction requires knowing every name worth removing, and the one nobody thought of is the one that leaks. Starting empty means a new credential in the launcher's environment cannot become a new leak in the sandbox.

## Alternatives considered

**Securing execution after building it.** Rejected as the order that produces untested boundaries.

**Running Karate against the sandbox now, with limits.** Rejected outright: that is user-controlled hostile content, and the prerequisites for admitting it do not exist.

**A configuration-inspection gate.** Rejected. Reading back the setting that requested a control proves the request, not the enforcement — and the read-only-root defect below is exactly what that failure mode looks like.

**Pinning the probe image by tag.** Rejected. A tag is a mutable pointer to executable code, which is a supply-chain hole in the one component whose entire purpose is being trustworthy.

## Consequences

- KAA-004 moves from open to **evidenced for a trusted probe** — not to closed. Closing it requires a stronger kernel boundary and the capability models named below.
- `services/runner` gains a container-runtime dependency, guarded in both directions: the control plane may not acquire it, and the launcher may not acquire Karate, an object store, or a secret provider.
- The security tests require a working Docker daemon. They are a mandatory CI job rather than a suite that skips silently when one is absent, because a security gate that quietly does not run is worse than one that fails.
- Sandboxes are labelled and reconciled, so a launcher that dies does not leave work behind permanently.

## Residual risk

Kernel-shared isolation is the primary one and is the reason this is not a user-content boundary. Beyond it: the launcher's own daemon access is confined rather than eliminated; host-dependent hardening varies; the image supply chain is digest-pinned but unsigned, unscanned, and without an SBOM; orphan cleanup depends on a reconciler actually running; and CPU quota lacks behavioural evidence.

## Prerequisites before user content may enter the sandbox

1. A stronger kernel boundary (gVisor or microVM), with this gate re-run against it.
2. Source capability issuance — what it binds to, how long it lives, whether it survives fencing, how it is revoked.
3. Secret capability issuance, on the same terms, with its own threat model.
4. An egress policy model replacing deny-all.
5. Artifact and output handling for genuinely untrusted content.

Each is its own slice with its own adversarial review. None of them is unlocked by this ADR.

## Adjudication (2026-09-05, ADR-029 slice)

This section is an amendment. Nothing above it has been rewritten, because the reason ordinary Docker was
refused for user content is still the reason, and an ADR that reads as though gVisor existed from the start
would be a worse record than one that shows the prerequisite being met.

### What the five prerequisites became

| # | Prerequisite | Status | Where |
| --- | --- | --- | --- |
| 1 | A stronger kernel boundary, **with this gate re-run against it** | **SATISFIED** | ADR-028. The same probe and the same control contract, under gVisor, in a mandatory CI job |
| 2 | Source capability issuance | **SATISFIED for the model; unredeemed** | ADR-023. It binds organization, project, run, attempt, epoch and expiry, and dies with its assignment |
| 3 | Secret capability issuance | **NOT APPLICABLE to first byte delivery** | ADR-023 models it; no provider exists and no secret is resolved. Inert source bytes need none |
| 4 | An egress policy model replacing deny-all | **SATISFIED** | ADR-026. `ALLOWLIST` through a proxy that is the sandbox's only peer, refused rather than downgraded |
| 5 | Artifact and output handling for genuinely untrusted content | **SATISFIED for output; OPEN for input** | Output is bounded, sanitised at the collector and reduced to a boolean. Input handling is what the source-delivery slice must establish |

Prerequisite 5 is the one that had no slice of its own, and it splits. What leaves the sandbox is handled:
the ceiling is corroborated against bytes actually retained, control and Unicode format characters are
stripped where they are collected, and the only sandbox-authored value that reaches a result is a comparison
against the literal `PASSED`. What *enters* the sandbox is not handled, because nothing tenant-authored
enters it yet — and that is precisely the next slice's job.

### What this amendment permits

Exactly one thing: a future slice may deliver **inert tenant-authored source bytes** into the mediated
sandbox, read-only, to be hashed and inspected by a platform-owned verifier.

### What it does not permit

Karate. Tenant source execution. Arbitrary commands, images, entrypoints or mounts. Production secret
resolution. Unrestricted network access. None of these is unlocked by this amendment, and none becomes easier
to unlock because of it.

### What is accepted rather than solved

The mediated runtime reduces direct host-kernel exposure; it is not a virtual machine, and the sentry remains
a host process. `NO_NEW_PRIVILEGES` is not observable under it — reported `UNSUPPORTED`, never passed — and
the two escalation paths it would block are closed instead by `NO_SETUID_BINARIES` and by an empty capability
bounding set, both observed. Runtime evidence can outlive the runtime it describes for up to the freshness
window, because the attestation binds the runtime family rather than the binary.

These are stated in full, with their consequences, in
[docs/security/hostile-content-readiness.md](../security/hostile-content-readiness.md).

## Validation

Every mandatory control is demonstrated by the probe from inside the sandbox, and each is mutation-verified along two axes.

**Removing the control** — ten mutations, all of which turn tests red. That found a real defect: the read-only-root check was passing on file permissions rather than on the mount, and would have kept passing with the control switched off.

**Removing the evidence** — the axis the first battery never touched, and where the serious defects were. Every mutation above left the probe reporting normally, so nothing tested what happened when an observation simply did not arrive. Five mandatory controls scored absence as a pass, and `blocksRelease() -> return false` — a gate rendered incapable of blocking anything — left the entire suite green. Sixteen controls are now driven to FAIL from a fake launcher in both modes, reported-off and absent; that same mutation now turns 38 of 40 tests red.

The general rule this ADR now records: **a gate that has only ever been observed green is not evidence.** A control must have a demonstrated red path before its green result means anything, and absent evidence must fail closed. Both properties are enforced by tests that fail if either regresses.
