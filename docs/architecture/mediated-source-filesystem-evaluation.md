# Mediated source filesystem: evaluation

Which filesystem can carry tenant source into a gVisor sandbox and actually enforce read-only, noexec, nosuid
and nodev — decided by measurement on real Linux with the pinned runtime, because macOS cannot register
`runsc` and no amount of reading settles it.

Every number below comes from the `gvisor-filesystem-experiment` workflow, five rounds, both runtimes.

## The question KAAS-18 left

A `local`-driver bind arrives in a mediated sandbox as a gofer-backed 9p mount carrying `ro` and nothing
else. Measured again here: a shebang script with an executable bit on that mount **executed**. Production
source did not execute only because the materialiser writes `0444` — a property of the file, not of the
filesystem. This evaluation exists to find a filesystem that makes it a property of the filesystem.

## What each candidate actually did

| | A: gofer bind | B: plain `-v ...:ro` | C: tmpfs asking for the flags | E: tmpfs declared read-only | **T: tmpfs populated then frozen** |
|---|---|---|---|---|---|
| reaches the sandbox as | 9p | 9p | tmpfs | tmpfs | tmpfs |
| `ro` reported | yes | yes | no | yes | **yes** |
| `noexec` reported | **no** | no | **yes** | **yes** | **yes** |
| `nosuid` reported | **no** | no | **yes** | **yes** | **yes** |
| `nodev` reported | **no** | no | **no** | **no** | **no** |
| can be populated | yes | yes | yes | **no — empty forever** | **yes** |
| executable file runs | **yes** | yes | no | n/a (empty) | **no** |

A and B fail on `noexec`, which is the whole point. C has the flags and no read-only. E has read-only and no
contents — a tmpfs declared read-only at container start can never be written, so it is a filesystem with
nothing on it. Only T satisfies both halves, and it does so by separating construction from consumption.

## The mechanism that works

A tmpfs is mounted writable with `noexec,nosuid,nodev` and a size bound. A platform-owned bootstrap, running
before anything else in the sandbox, receives the bundle on a fixed binary channel, writes regular files,
then **remounts the filesystem read-only** and drops every capability before handing over to the verifier.

The remount is the load-bearing step and it is runtime-specific:

| | runc | runsc |
|---|---|---|
| `mount -o remount,ro` with `CAP_SYS_ADMIN` | **Permission denied** | **OK** |

Docker's seccomp profile refuses `mount` under runc even with the capability. Under gVisor the sentry
implements `mount` itself, so no host mount syscall is involved and the sandbox can close its own filesystem.
The mechanism therefore exists *because* the runtime is mediated, which is an unusual and worth-stating
inversion: the stronger runtime is the one that permits this, and the weaker one cannot.

## What the consumer observes after the freeze

```
identity: uid=65534(nobody) gid=65534(nobody)
/kaas/source: tmpfs ro,noexec,nosuid,size=8192k
mountinfo:    / /kaas/source ro,noexec,nosuid - tmpfs none ro,size=8192k
CapInh CapPrm CapEff CapBnd CapAmb: all 0000000000000000
reremount=REFUSED        hardened_write=REFUSED
exec_control=EXECUTED    exec_hardened=REFUSED
```

`exec_control` and `exec_hardened` are the same file, mode `-r-xr-xr-x`, planted on two filesystems that
differ only in their mount flags. It runs on one and is refused on the other. That is `noexec` being
enforced, and it is a different claim from anything KAAS-18 could make.

## The negative controls, all of which fire

| control | result |
|---|---|
| no `CAP_SYS_ADMIN` | `bootstrap_failure=FREEZE` — no frozen filesystem, no ready state |
| a frame larger than the filesystem | `bootstrap_failure=STAGING` |
| a truncated frame | `bootstrap_failure=TRUNCATED` |
| the same mechanism under runc | `bootstrap_failure=FREEZE` |
| identical executable on a permissive mount | executes, so its refusal elsewhere means something |

## Where the evidence stops, and why

### nosuid: reported, but its red path cannot exist under this runtime

A setuid-root binary was placed on a fully permissive tmpfs — no `nosuid`, no no-new-privs — and executed by
an unprivileged user:

| | runc | runsc |
|---|---|---|
| permissive mount | `ruid=65534 euid=0` — **escalated** | `ruid=65534 euid=65534` — **no escalation** |
| `nosuid` mount | `ruid=65534 euid=65534` | `ruid=65534 euid=65534` |

**gVisor performs no setuid transition at all.** So `nosuid` is reported on the frozen mount, and the property
it protects is unreachable on this runtime whatever any mount says. There is no honest red path for the flag,
because the runtime removes the hazard globally rather than per-mount.

Two earlier readings of this were wrong and are corrected here rather than quietly dropped. An earlier round
recorded `suid=ESCALATED` under gVisor; that container ran as root, so `euid=0` was where it started and not a
transition. A later round blamed a broken control on the fixture; the actual cause was the bootstrap's own
`PR_SET_NO_NEW_PRIVS`, which forbids escalation for every descendant regardless of the mount.

### nodev: not reported, and not honoured

| | runc | runsc |
|---|---|---|
| tmpfs mounted with `nodev` reports it | yes | **no** |
| `mknod` on that filesystem | created | created |
| reading the device node | **refused** | **succeeds** |

gVisor does not implement `MS_NODEV`. It is absent from the option list of every tmpfs measured — one that
asked for it, Docker's own read-only tmpfs, and the frozen mount — and a device node on such a filesystem
still behaves as a device. Under runc the identical test refuses the read.

This is a runtime limitation and it is not closed by this slice. What stands in its place is not a mount
flag: the frozen filesystem is read-only, the consumer's bounding set is empty so it holds no `CAP_MKNOD`,
and the bundle format carries a path and bytes and cannot express a device node. Three independent layers,
none of which is the flag that was asked for — which is exactly the shape of argument KAAS-18 was rejected
for, and it is recorded as a gap rather than as an equivalence.

## Candidates not selected

**A gofer bind, retained as ingress.** Rejected outright. If a weaker copy of the source is reachable from
the consumer, hardening the other copy is cosmetic — hostile code does not use the path it was meant to. The
selected design has no host mount of tenant source at all.

**A per-run image layer.** Building an image per execution would give a read-only, flag-carrying filesystem,
and it puts tenant bytes into the image store, adds a build step to every run, and creates a retention
problem this slice would then have to solve. Rejected on cost and blast radius.

**FUSE or a custom filesystem.** Rejected under the slice's own rule: a new daemon must beat the simpler
option on security and operability, and a tmpfs the sentry already implements beats it on both.

**Object storage for ingress.** Out of scope and orthogonal: it changes where bytes come from, not what the
filesystem enforces.

## Selected

The frozen sandbox-private tmpfs, populated by a trusted bootstrap over stdin, with no host mount of tenant
source anywhere in the sandbox. It closes `ro` and `noexec` — the KAAS-18 blocker — reports `nosuid` on a
runtime that performs no setuid transitions, and leaves `nodev` measured, unenforced and unclaimed.
