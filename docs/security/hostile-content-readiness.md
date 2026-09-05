# Hostile-content readiness

**Status: ADJUDICATED.** See [ADR-022](../adr/022-hostile-execution-boundary-and-synthetic-probe.md) for the
original prerequisite and `HOSTILE_CONTENT_READINESS_SLICE_REPORT.md` for the evidence behind every claim
here.

## What may enter the sandbox today

A platform-owned synthetic probe, and nothing else. Its command is an enum constant, its image is built from
this repository and pinned by digest, and no tenant-supplied value reaches the launcher.

## What may enter after this adjudication

**Inert tenant-authored source bytes**, delivered read-only under a dedicated future slice, hashed and
inspected by a platform-owned verifier.

That is the entire capability unlocked. Nothing about it permits execution.

## What may not execute

- Karate, or any test engine
- Tenant-authored source, in any form
- Arbitrary commands, images, entrypoints or mounts
- Anything holding a tenant secret

## The boundary used

The mediated gVisor profile (`kaas.sandbox.gvisor.v1`), with no fallback to the baseline runtime, under
continuous execution authority. Concretely:

| Property | Enforcement |
| --- | --- |
| Runtime | Requested via the daemon **and** confirmed from inside the sandbox by the guest kernel's own name |
| Fallback | None. An unavailable or substituted runtime refuses the sandbox rather than downgrading it |
| Privilege | Empty capability bounding set, no setuid or setgid binaries, read-only root, non-root uid |
| Filesystem | Read-only root; the only writable paths are tmpfs mounted `noexec,nosuid,nodev` |
| Network | `DENY_ALL`, or `ALLOWLIST` through a proxy that is the sandbox's only peer on a per-execution internal network |
| Duration | Bounded by the profile deadline **and** by continuous execution authority |
| Output | Byte-bounded, control- and format-character stripped at the collector, and reduced to a boolean before it becomes a result |

## Residual risks accepted for inert byte delivery

These are accepted **for bytes that do not execute**. Each must be revisited before execution.

1. **The sentry is a host process, not a virtual machine.** gVisor mediates syscalls in userspace and is
   itself seccomp-confined against the host. This reduces direct host-kernel exposure; it does not eliminate
   it, and a sentry compromise is host-adjacent rather than impossible.
2. **Two additional interfaces exist inside the sandbox** — `/dev/fuse` and `/dev/net/tun` — both served by
   the sentry rather than the host, and both permitted only under this runtime.
3. **`NO_NEW_PRIVILEGES` is not observable** under this runtime. It is applied and reported `UNSUPPORTED`,
   never passed. See below for why the compensating controls are judged sufficient.
4. **Evidence can outlive the runtime it describes, for up to the freshness window.** The attestation binds
   the runtime *family*, the daemon instance and the profile version — not the `runsc` binary digest. An
   operator who upgrades the runtime without regenerating evidence keeps valid evidence describing the
   previous release for at most `kaas.execution.attestation-max-age` (24 hours by default).
5. **The image supply chain is digest-pinned but unsigned and unscanned**, with no SBOM. Unchanged from
   ADR-022.

## Why the missing `NoNewPrivs` observation is judged acceptable

`no_new_privs` exists to stop a process gaining privilege through `execve`. There are exactly two mechanisms
it blocks, and each is closed independently and **observably**:

| Escalation path | Closed by | Observed? |
| --- | --- | --- |
| setuid / setgid binary | `NO_SETUID_BINARIES` — no such file exists anywhere in the sandbox | yes, mandatory |
| file capabilities | `CAPABILITIES_DROPPED` — the bounding set is empty, and file capabilities cannot grant anything outside it | yes, mandatory |

The scan covers setuid and setgid bits and does **not** read file-capability attributes. It does not need to:
an empty bounding set is a ceiling that file capabilities cannot raise, and that ceiling is separately
observed. This is recorded as joint coverage rather than as one control doing both jobs.

Both are properties of a read-only root filesystem, so nothing inside the sandbox can create the file that
would defeat them.

## What would invalidate this readiness

Any of the following requires regenerating and re-verifying runtime security evidence before it may be
relied on again:

- a different `runsc` release, or a change to the pinned digest
- a change to the host kernel of a machine that runs sandboxes
- a change to the runtime registration in the daemon
- a change to the security profile, its controls, or their enforcement levels
- a change to the probe image or the egress proxy image
- a change to the mandatory control set for either profile version

**A runtime pin change is not an ordinary dependency bump.** The pin this repository carries was itself
changed after the previous release was measured to refuse every network mode on a newer kernel — the same
class of change can alter what the boundary enforces, and evidence gathered before it describes a different
system.

## What the next slice must satisfy

Delivering inert bytes is not free. The source-delivery slice must establish, with red-path evidence:

1. The bundle is mounted **read-only** and with `noexec,nosuid,nodev`. Without `nosuid`, a tenant-supplied
   file could defeat `NO_SETUID_BINARIES`, which is one of the two controls compensating for the missing
   `NoNewPrivs` observation.
2. Logical paths cannot escape the bundle root — traversal, absolute paths, symlinks, and duplicate or
   Unicode-normalisation-colliding names all resolve inside it or are refused.
3. Aggregate and per-file size ceilings, enforced before extraction rather than after.
4. The bundle digest is verified and the bundle is immutable between verification and mount.
5. No source byte reaches a shell, a launcher argument, a container setting, or a path used by the host.
6. `NO_SETUID_BINARIES` is re-verified **with a bundle present**, not only on the bare image.
