# Mediated source filesystem

Where tenant source lives inside a sandbox, what the filesystem holding it actually enforces, and — stated
before anything else, because it is the part most easily skipped — what it does not.

## What changed, and what did not

KAAS-18 delivered source by binding a host directory into the sandbox. Under the mediating runtime that
arrives as a gofer-backed 9p mount carrying `ro` and nothing else, and a shebang script placed on it
**executed**. Production source did not execute because the materialiser writes `0444` — a property of the
file, not of the filesystem.

The source filesystem is now a tmpfs the sandbox creates for itself, populated over a byte stream and then
frozen from the inside. `noexec` is enforced by the mount, and that is measured against a file that is
genuinely executable.

`nodev` is still not enforced. That gap moved rather than closed, and §"The gap" below says exactly what it is.

## The path a byte takes

```
SourceCapability (assignment-scoped, redeemed by trusted runner code, plaintext only in the envelope)
        |
        v
runner verifies the bundle against the COMMAND — exact feature set, per-entry digest, aggregate digest
        |
        v
runner frames it: magic, count, and per entry a length, a path, a digest, and that many bytes
        |
        |   standard input. Not a mount. Not an argument. Not an environment variable. Not shell text.
        v
platform bootstrap, first process in the sandbox, root, holding CAP_SYS_ADMIN
        |
        +-- writes regular files at 0444 into a private tmpfs
        +-- writes the platform manifest
        +-- FREEZES the filesystem: remount ro,noexec,nosuid,nodev
        +-- drops every capability, clears the bounding set, sets no-new-privs
        +-- becomes uid 65534
        |
        v
platform verifier — unprivileged, on the frozen filesystem, hashes every file against the manifest
```

**There is no ingress filesystem.** The weaker copy KAAS-18 mounted does not exist: the bytes are in the
runner's memory, then in a pipe, then in the sandbox's own filesystem. Nothing tenant-derived is ever bound in
from the host, and the verifier reports the number of 9p mounts it can see, which is zero.

## What the filesystem enforces

| property | mount reports | behavioural evidence | verdict |
|---|---|---|---|
| read-only | `ro` | a write is refused, and the consumer cannot remount it writable | **enforced** |
| noexec | `noexec` | an identical `0555` executable runs on a permissive tmpfs in the same sandbox and is refused here | **enforced** |
| nosuid | `nosuid` | none possible — see below | **reported** |
| nodev | **absent** | a device node on a nodev-requested tmpfs still behaves as a device under this runtime | **not enforced** |

### noexec, and why the control matters

The fixture has mode `0555` and valid shebang content. It is planted on two filesystems in the same sandbox
that differ only in their mount flags, and it is run on both: `exec_control=EXECUTED`,
`exec_hardened=REFUSED`. Without the first half, the second is equally consistent with a file that was never
runnable — which is the ambiguity KAAS-18 ended on.

The fixtures are written by a separate binary, selected by a separate server-side workload, reachable from
nothing on the delivery path. The production bundle format still carries a path and bytes and cannot express
a mode.

### nosuid has no red path on this runtime

A setuid-root binary on a **fully permissive** tmpfs, executed by an unprivileged user with no no-new-privs
anywhere, does not escalate under gVisor:

| | runc | runsc |
|---|---|---|
| permissive mount | `ruid=65534 euid=0` | `ruid=65534 euid=65534` |
| `nosuid` mount | `ruid=65534 euid=65534` | `ruid=65534 euid=65534` |

gVisor performs no setuid transition at all. The flag is reported on the frozen mount and the hazard it names
is unreachable on this runtime whatever any mount says — so there is no configuration in which its absence
would be observable, and claiming it was demonstrated would be claiming a test that cannot exist. The suite
asserts the control's *non*-escalation, so a runtime that starts performing transitions fails it.

### The gap: nodev

gVisor does not implement `MS_NODEV`.

| | runc | runsc |
|---|---|---|
| a tmpfs mounted with `nodev` reports it | yes | **no** |
| `mknod` on that filesystem | created | created |
| reading the device node | **refused** | **succeeds** |

The flag is absent from every tmpfs measured — one that asked for it, the runtime's own read-only tmpfs, and
the frozen source mount. What stands in its place is not a mount flag:

1. the frozen filesystem is read-only, so nothing can be created on it;
2. the consumer's bounding set is empty, so it holds no `CAP_MKNOD` — read out of `/proc` by the consumer
   itself;
3. the bundle format carries a path and bytes and cannot express a device node.

Three independent layers, none of them the one that was asked for. That is the same shape of argument KAAS-18
was rejected for, and it is recorded here as a gap rather than as an equivalence. **This is why KAAS-19 does
not close the boundary.**

## The construction privilege

The container's first process is the bootstrap, running as root with `CAP_SYS_ADMIN`, `CAP_SETUID`,
`CAP_SETGID` and `CAP_SETPCAP`. This is a material change and is adjudicated in ADR-031. In short:

- **It is a capability inside the sentry.** The mount it performs is a mount in the sandbox's own filesystem
  tree, implemented in userspace by gVisor. No mount syscall reaches the host kernel. The identical request
  under the baseline runtime is refused outright, measured.
- **No tenant byte influences a privileged operation.** The mount target, the flags and the program handed
  over to are compile-time constants. The stream supplies paths and bytes, and paths are checked against the
  same rules the control plane and the runner already applied.
- **None of it survives.** The consumer reports `CapInh`, `CapPrm`, `CapEff`, `CapBnd` and `CapAmb` all empty,
  `NoNewPrivs=1`, uid 65534, and a refused remount. Read out of `/proc` by that process, not asserted by the
  program that dropped them.
- **Failure is closed.** Without the capability the freeze fails and the bootstrap reports
  `bootstrap_failure=FREEZE` — no frozen filesystem, no verification, no ready state.

## Where source plaintext exists, and for how long

| stage | who can read it | how long | how it ends |
|---|---|---|---|
| control-plane database | the control plane | the FeatureRevision's life | out of scope; sealed and immutable |
| runner memory | the runner process | one execution | garbage collected; never written to a disk |
| the pipe | the daemon and the container | milliseconds | closed after one write |
| the sandbox tmpfs | the sandbox | the container's life | memory, freed with the container |

**No host filesystem stage at all.** KAAS-18 had one and needed a reconciler for the copies a crashed host
left behind; that orphan class no longer exists, so the reconciler was deleted rather than left sweeping a
directory nothing writes to.

## What is deliberately absent

No Karate and no Karate dependency. No `.feature` parsing by anything. No shell anywhere on the source path —
the bootstrap is a static binary that reads a stream, and it hands over to a fixed interpreter running a fixed
script with a mode word matched against two literals. No tenant-supplied binary, entrypoint, image, runtime,
mount option or container setting. No secret resolution or injection. No object storage.

**Tenant source remains inert.** It is stored, transported, written, hashed and compared. Nothing parses it,
executes it, sources it, or hands it to an interpreter.
