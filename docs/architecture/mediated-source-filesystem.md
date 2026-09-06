# Mediated source filesystem

How tenant-authored bytes get from a sealed database row onto a filesystem inside a gVisor sandbox that
refuses to execute them, which resources exist along the way, and who owns each one. The security
adjudication — including the gap this design does not close — is in
[`docs/security/mediated-source-filesystem.md`](../security/mediated-source-filesystem.md); the measurements
behind the design choice are in
[`mediated-source-filesystem-evaluation.md`](mediated-source-filesystem-evaluation.md).

## Shape

```
FeatureRevision (immutable, trigger-enforced)
        |
        |  snapshot selection, at run creation
        v
ExecutionCommand
    sourceBundle.contentDigest      <- aggregate, covered by the command digest
    sourceBundle.features[]         <- featureId, revisionId, logicalPath, contentDigest
        |
        |  delivery envelope carries sourceCapabilityToken (NOT in the command, NOT in its digest)
        v
Runner  (host, trusted)
    redeem   -- internal API, one attempt, bounded response, run must still be CLAIMED
    verify   -- against the COMMAND, never against what the response says about itself
    frame    -- magic | bundle digest | count | (len, path, digest, size, bytes)* | trailer
        |
        |  container stdin. No mount. No argv. No environment. No shell.
        v
Sandbox  (mediated runtime)
    /source-bootstrap    root + CAP_SYS_ADMIN, first process
        |
        +-- writes 0444 regular files into a private tmpfs
        +-- writes the platform manifest
        +-- remounts it ro,noexec,nosuid,nodev          <- the freeze
        +-- clears the bounding set, sets no-new-privs, becomes 65534
        |
        v
    /probe.sh sourceverify    unprivileged consumer, on the frozen filesystem
        +-- reads its own capability sets out of /proc
        +-- reads the mount's flags out of both mountinfo option namespaces
        +-- hashes every file against the manifest
        +-- emits one authoritative PASSED / FAILED
```

## The two phases, and why the boundary is between them

**Construction** holds a capability and touches tenant bytes. **Consumption** holds nothing and is the process
a future engine would be. Everything the platform claims about the boundary is measured in the second phase,
because a mount flag observed by a privileged process says nothing about what an unprivileged one can do.

The transition is one-way and is proven from the far side: the consumer reads `CapInh`, `CapPrm`, `CapEff`,
`CapBnd` and `CapAmb` out of `/proc` — all empty — and confirms it cannot remount the filesystem it is
reading.

## Why the frame is not the ZIP

The bundle crosses the control-plane boundary as a deterministic STORED ZIP and is verified as one, by the
runner, against the command. That is unchanged.

What changed is what crosses into the sandbox. Handing the archive to the bootstrap would put a ZIP parser
inside the most privileged program in the sandbox, where optional fields, compression methods and
central-directory quirks all become attack surface at the worst possible moment. The frame is deliberately the
dullest format that can carry the job: nothing optional, nothing compressed, nothing indexed, every length
read before the bytes it describes, and a trailer so a truncated stream is a refusal rather than a short
bundle.

Entry names are still not authoritative. The bootstrap checks every path against the same rules the control
plane and the runner already applied, and joins it under a compile-time constant root.

## Resource ownership

| resource | created by | owned by | normal cleanup | failure cleanup | crash |
|---|---|---|---|---|---|
| framed bundle | runner, in memory | the execution | garbage collected | garbage collected | dies with the process |
| stdin stream | launcher | the launch | closed after one write | closed | closed by the daemon |
| source tmpfs | the runtime, at container create | the container | freed with the container | freed with the container | freed with the container |
| staged files | bootstrap | the tmpfs | — | — | — |
| container | launcher | the launcher | removed after observation | removed on every failure path | orphan reconciler |

**Nothing is on the host.** KAAS-18 had a staging directory and needed a reclaimer for the copies a crashed
host left behind. That orphan class no longer exists, so the reclaimer was deleted rather than left sweeping a
directory nothing writes to — a cleaner with nothing to clean is a mechanism nobody can observe working.

## What a future engine would see

One filesystem at `/kaas/source`: a tmpfs, `ro,noexec,nosuid`, holding regular `0444` files and a manifest,
with the bytes the command authorized and nothing else.

It would **not** see a 9p mount, a host directory, an ingress copy, a writable staging area, a capability, or
a way to reopen the filesystem. The verifier reports the number of source mounts it can see (one) and the
number of gofer mounts it can see (zero), so those are assertions rather than intentions.

It would also not see `nodev`, because the runtime does not implement it. See the security document.

## Where the boundary is decided

| decision | made by | not made by |
|---|---|---|
| whether source is delivered at all | operator configuration on the runner | anything in the command |
| whether this runtime may deliver it | the runner, refusing any non-mediated runtime | a retry, a flag, or a fallback |
| the filesystem type, path and flags | compile-time constants in the launcher and the bootstrap | the profile, the command, or the tenant |
| the filesystem's size | the shared bundle contract | the bundle |
| what runs in the sandbox | a server-side probe enum with fixed argument vectors | the source |
| when the filesystem closes | the bootstrap, after the last write and before any privilege is dropped | the consumer |

## Contract

`packages/api-contracts/source-bundle.json` holds the format version, the limits, the mount layout and the
filesystem size. Two modules must agree on them and neither may import the other, so each has a contract test
asserting its own constants equal that file. The probe is a shell script and the bootstrap is a C program;
neither can import a Java constant, so a further test asserts they look for the bundle where the contract puts
it.
