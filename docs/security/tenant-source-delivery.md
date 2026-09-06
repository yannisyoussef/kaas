# Tenant source delivery

What happens to a tenant's bytes between the database and a sandbox, what is enforced along the way, and —
stated first because it is the part most likely to be skipped — what is **not**.

## The gap, up front

**`noexec` is not enforced on the source mount under the mediating runtime.**

This was measured twice in CI, on both runtimes, with the same requested configuration:

| | baseline runtime (`runc`) | mediating runtime (`runsc`) |
|---|---|---|
| filesystem seen by the sandbox | `ext4` | `9p` (gofer-backed) |
| mount options carried | `ro,nosuid,nodev,noexec` | `ro` |
| a write into the mount | refused | refused |
| a script in the mount, with an exec bit, executed | refused | **executed** |

The last row is the finding. A Docker `local`-driver bind requested with `o=bind,ro,noexec,nosuid,nodev`
arrives under the baseline runtime as an ext4 mount carrying all four flags; under the mediating runtime the
same request arrives over the gofer as a 9p mount carrying `ro` and nothing else, and an executable file
placed in it ran.

A second round established that gVisor honours `noexec` and `nosuid` on **tmpfs** by default — the no-flags
control behaved identically, so the flags were not what produced it — and that tmpfs cannot be populated from
the host. So there is no configuration of this mount, on this runtime, that carries `noexec` today.

### What refuses execution instead

Two barriers, neither of which is the mount:

1. **The format cannot express a mode.** A bundle entry is a logical path and bytes. There is no field for a
   permission, an owner, a link target or a device number, so there is nothing for a hostile bundle to set.
2. **The materialiser writes `0444`.** Every staged file is created read-only with no executable, setuid or
   setgid bit, whatever the transport contained. Directories are `0700` and platform-owned.

The strong-runtime gate observes the *outcome* — `source_exec_refused=true` — from inside a mediated sandbox,
and separately records `source_mount_noexec=false`, so the weaker configuration cannot hide behind the
stronger barrier. Both are asserted in the direction each is actually true: if a future runtime release starts
carrying `noexec`, the gate fails and this document is re-adjudicated rather than quietly becoming stale in
the platform's favour.

### What this gap means

It means the requirement was not met and was not downgraded. Delivering **inert** bytes is safe under the
barriers above, because nothing in this slice hands a tenant byte to anything that could run it. It is not a
basis for executing tenant code, and **KAAS-18 does not declare tenant code execution ready**. Closing this
gap is a prerequisite for that decision, alongside the runtime-pin attestation gap recorded in KAAS-15.

## What is enforced

| property | how it is enforced | how it is observed |
|---|---|---|
| read-only mount | bind declared read-only | mount options **and** a refused write, from inside the sandbox, both runtimes |
| exact authorized bytes | digest per entry, plus an aggregate bundle digest | re-hashed from the mounted view by the platform's verifier |
| exactly the authorized set | set comparison against the command's feature list | entry count and per-entry presence, in-sandbox |
| no setuid or setgid material | format cannot express a mode; materialiser writes `0444` | `find -perm -4000 -o -perm -2000` under the mount, in-sandbox |
| only regular files | materialiser creates nothing else; `CREATE_NEW` cannot follow a link | `find ! -type f ! -type d` under the mount, in-sandbox |
| no execution of tenant bytes | no code path passes source to an interpreter, shell or launcher | an execution attempt on a mounted file, in-sandbox |
| bytes do not outlive the execution | staging is `AutoCloseable` and owned by a try-with-resources | staging directory absent after the run; a reconciler reclaims what a crash left |

## The path a byte takes

1. **Sealed.** A `FeatureRevision`'s source is immutable — enforced by a database trigger, so there is no
   supported operation that rewrites bytes a command already authorized.
2. **Named.** The run's snapshot selects revisions. The `ExecutionCommand` carries, for each, a logical path
   and a content digest, plus one aggregate bundle digest, all covered by the command digest.
3. **Delivered.** The worker redeems an assignment-scoped `SourceCapability` over the internal API. The
   plaintext token lives in the delivery envelope and nowhere else.
4. **Verified on the host.** The runner walks the **command's** feature list, not the archive's entry names,
   and refuses anything extra, missing, altered, oversized or unsafely named.
5. **Staged.** Regular files at `0444` under an opaque directory of the operator's staging root, written with
   `CREATE_NEW` so nothing existing can be followed or overwritten.
6. **Mounted.** Read-only, at a fixed platform-owned container path. Neither side of the bind derives from
   tenant input.
7. **Re-verified inside the sandbox.** The platform's verifier re-hashes what it can actually see. This is the
   authoritative check: the host's verification describes a moment that has passed.
8. **Removed.** The try-with-resources that owns the staging closes on every path, including failure. A
   reconciler reclaims bundles a crashed host left behind, matching only the platform's own directory prefix
   and only past a grace period, so it can never take a live execution's source.

## The capability

The plaintext token is bearer authority. It must never appear in the ExecutionCommand, the command digest,
PostgreSQL plaintext, the broker, the outbox or inbox, container labels, filesystem names, metrics, logs,
exceptions, or CI output. It rotates on every delivery, which is also why a digest could not cover it.

Redemption happens while the run is still `CLAIMED`, because that is the lifecycle the control plane has
enforced since capabilities existed. Materialisation waits until `PROVISIONING` is announced, so no tenant
byte reaches a disk before a phase deadline and a reconciler are accountable for it. Execution authority is
re-read before the transfer, after it, and again before the write.

## What is deliberately absent

No Karate and no Karate dependency. No `.feature` parsing, by anything. No shell invocation with source
content. No tenant-supplied binary, entrypoint, image, runtime or container setting. No secret resolution or
injection. No object storage, artifacts or reports. Tenant source is never used as a path outside the bundle,
as network configuration, or as a command.
