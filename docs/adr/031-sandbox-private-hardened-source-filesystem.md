# ADR-031: Sandbox-private hardened tenant source filesystem

**Status: ACCEPTED.** Supersedes ADR-030's delivery mechanism. Does not close ADR-022, and does not approve
executing tenant content.

## Context

ADR-030 delivered inert tenant source by mounting a host directory into the sandbox, and recorded honestly
that the mount did not carry `noexec` under the mediating runtime. That was measured, not suspected: a
gofer-backed 9p mount arrives with `ro` and nothing else, and a shebang script on it executed.

Execution was refused in production only because the bundle format carries no mode and the materialiser writes
`0444`. That is a property of the file. `noexec` is a property of the filesystem. The two are different
claims, and the second is the one a future engine's safety would rest on.

### Why `0444` is not equivalent to `noexec`

It holds only while every path that writes into the source filesystem chooses to write non-executable files.
That is one defect, one new call site, or one future feature away from being false, and nothing about the
filesystem would notice. A mount flag holds regardless of what wrote the file.

### Why the gofer bind cannot close it

The flags are dropped in translation, not in the request. Docker passes them; the mount that reaches the
sandbox does not carry them. There is no configuration of a host bind, on this runtime, that carries `noexec`
— measured across five rounds and both runtimes.

## Decision

### The source filesystem is a sandbox-private tmpfs, populated then frozen

A tmpfs the sandbox creates for itself does honour `noexec` and `nosuid` under this runtime. It cannot be
populated from the host, and one declared read-only at create time can never be written and so never holds
anything. So the filesystem is created writable, populated from inside, and closed from inside.

### There is no ingress filesystem

Tenant source is not mounted from the host at all. The runner frames the verified bundle in memory and writes
it to the container's standard input. A weaker second copy reachable from the sandbox would make hardening
the first one cosmetic, because hostile code does not use the path it was meant to.

### A trusted bootstrap holds a construction capability, briefly

The container's first process is a platform-owned static binary, built from this repository's source in a
digest-pinned stage. It runs as root with `CAP_SYS_ADMIN`, `CAP_SETUID`, `CAP_SETGID` and `CAP_SETPCAP`; it
reads the frame, writes regular files at `0444`, writes the manifest, remounts the filesystem read-only, drops
every capability, becomes uid 65534, and execs the verifier.

**This is a material boundary change and is treated as one.** The argument that it is acceptable rests on
four things, each measured rather than asserted:

1. **The capability is inside the sentry.** gVisor implements `mount` in userspace; the operation is a change
   to the sandbox's own filesystem tree and no mount syscall reaches the host kernel. The identical request
   under the baseline runtime is refused outright.
2. **No tenant byte influences a privileged operation.** The mount target, the flags, the modes and the
   program handed over to are compile-time constants. The stream supplies paths and bytes; paths are checked
   against the same rules the control plane and the runner already applied.
3. **The drop is proven, not requested.** The consumer reads its own `CapInh`, `CapPrm`, `CapEff`, `CapBnd`
   and `CapAmb` out of `/proc` — all empty — plus `NoNewPrivs=1` and a refused remount. "The bootstrap called
   capset" is a statement about a call.
4. **Failure is closed.** Without the capability the freeze fails, the bootstrap reports
   `bootstrap_failure=FREEZE`, and no verification and no ready state follow.

### Source delivery is refused on a runtime that cannot enforce the boundary

A worker whose runtime is not the mediating one refuses to frame the bundle at all, before a container exists.
Not attempted and abandoned: a worker that framed first would put tenant bytes inside a sandbox and only then
find it could not close the filesystem around them.

### Format enforcement is kept, not replaced

The bundle format still carries a path and bytes and cannot express a mode, a link or a device. Files are
still written `0444`, still regular, still non-setuid. Gaining a filesystem layer is not a reason to give up
the format layer — particularly here, where the format's inability to carry a device node is one of the
things standing in for a flag the runtime does not implement.

## What this closes, and what it does not

| property | before | after |
|---|---|---|
| read-only | enforced | enforced, and the consumer cannot reopen it |
| noexec | **not enforced** | **enforced**, proven against a genuinely executable fixture |
| nosuid | not enforced | reported; the runtime performs no setuid transition on any filesystem |
| nodev | not enforced | **still not enforced** |

### The gap that remains

gVisor does not implement `MS_NODEV`. The flag is absent from every tmpfs measured, and a device node on a
nodev-requested tmpfs still behaves as a device — while the identical test under the baseline runtime refuses
the read. What stands in its place is a read-only filesystem, an empty bounding set holding no `CAP_MKNOD`,
and a format that cannot express a device node.

That is three layers and none of them is the mount flag, which is the same shape of argument ADR-030 was
found wanting for. It is recorded as a gap. **ADR-022 stays open and tenant code execution stays
unapproved.**

## Consequences

- Tenant source now lives on a filesystem that refuses to execute it, rather than on one that would execute
  it if the file happened to be marked executable.
- The sandbox's first process is privileged for the length of one populate-and-freeze. That is new, and it is
  what the four points above exist to justify.
- No host staging, so no stale-source reconciler: the orphan class it swept no longer exists, and the
  reconciler was deleted rather than left sweeping a directory nothing writes to.
- Source delivery is now runtime-conditional. A baseline-runtime deployment refuses it rather than degrading.
- The `nodev` gap and the runtime-pin attestation gap are both prerequisites for the execution-readiness
  adjudication, which this ADR does not perform.

## Alternatives considered

**Keep the gofer bind and accept `0444`.** This is what ADR-030 did and what this ADR exists to replace.

**Keep the bind as ingress and harden a second copy.** Rejected: the weaker copy stays reachable, and a
future engine only has to look at the other path.

**A per-run image layer.** Would give a read-only flag-carrying filesystem, at the cost of putting tenant
bytes in the image store, a build step on every run, and a retention problem this slice would then own.

**FUSE or a custom filesystem.** Rejected under the rule that a new daemon must beat the simpler option on
security and operability; a tmpfs the sentry already implements beats it on both.

**Wait for a runtime that implements `MS_NODEV`.** Rejected, but stated rather than assumed: `noexec` is the
gap that blocked ADR-030 and it is closed here, the remaining gap is reported and gated in CI, and the
requirement was not downgraded to claim otherwise.
