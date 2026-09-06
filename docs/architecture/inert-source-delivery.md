# Inert source delivery

How tenant-authored bytes move from a sealed database row into a sandbox, and where each property is enforced.
The security adjudication, including the mount gap this slice did not close, is in
[`docs/security/tenant-source-delivery.md`](../security/tenant-source-delivery.md).

## Shape

```
FeatureRevision (immutable, trigger-enforced)
        |
        |  snapshot selection, at run creation
        v
ExecutionCommand
    sourceBundle.contentDigest        <- aggregate, covered by the command digest
    sourceBundle.features[]
        featureId, revisionId, logicalPath, contentDigest
        |
        |  delivery envelope carries sourceCapabilityToken  (NOT in the command, NOT in its digest)
        v
Runner
    redeem      -- internal API, one attempt, bounded response, run must still be CLAIMED
    verify      -- against the COMMAND, never against what the response says about itself
    stage       -- 0444 regular files under an opaque directory of the operator's staging root
    mount       -- read-only bind at a fixed platform-owned container path
        |
        v
Sandbox
    the platform's verifier re-hashes the MOUNTED bytes and emits one PASS/FAIL bit
```

## Why the archive's entry names are not authoritative

This is the property that makes reading an archive in the runner defensible at all.

The runner does not walk the archive and materialise the paths it finds. It walks the **command's** feature
list — platform-authored, covered by the command digest — and looks each authorized logical path up in what
arrived. The names inside the archive are used only as lookup keys and never reach a filesystem call.

The consequence is that the familiar archive attacks do not apply in their usual form:

- A traversing or absolute entry name cannot become a path, because no name from the archive is ever used as
  one.
- A duplicate entry cannot win a race, because lookup is by expected key.
- An extra entry is detected rather than extracted, because the set comparison runs in both directions.

The path rules are still applied — to the command's own paths — because a control-plane defect must not become
a traversal on a worker host, and because those paths are about to be joined onto a staging root.

## The two digests, and why there are two

**Per entry:** the content digest the snapshot recorded, compared against the bytes that arrived. This is what
stops one feature's source being substituted for another's.

**Aggregate:** a canonical semantic digest over the sorted `(logicalPath, contentDigest)` pairs, plus the
format version and the entry count, all length-prefixed. It is *not* a digest of the archive bytes: digesting
those would make a bundle's identity depend on the ZIP implementation that produced it, so a JDK upgrade could
change the digest of content that had not changed by a byte.

Both are computed twice by two independently written implementations — the control plane's and the runner's —
because one implementation agreeing with itself proves nothing. Both are exchanged in one form,
`sha256:<hex>`, enforced by a record invariant rather than by convention, after this slice found three call
sites disagreeing about whether the prefix was present.

## Where each property lives

| property | enforced by | not enforced by |
|---|---|---|
| bytes are immutable once authorized | database trigger on `feature_revisions` | the runner's digest check, which is the second line |
| the delivered set is the authorized set | set comparison in `SourceBundle.verified` | trusting the response's own manifest |
| no mode, link or device can arrive | the format has no field for one | validating a bundle for them |
| nothing is executable | the materialiser writes `0444` | the mount, under the mediating runtime — see the security doc |
| bytes do not outlive their execution | `SourceStaging` is `AutoCloseable`, owned by a try-with-resources | a cleanup call each branch has to remember |
| a crashed host's bytes are reclaimed | `StaleSourceReconciler`, on the execution path | a timer nobody starts in production |
| the mounted bytes are the authorized bytes | the in-sandbox verifier | the host-side check, which describes a moment that has passed |

## Two verifications, two windows

The host-side verification runs before anything is written. It covers delivery: a control-plane defect, a
corrupted transfer, a substituted entry.

The in-sandbox verification runs over the mount the workload actually sees. It covers the window between
staging and launch, which no host-side hash can describe — and it is the authoritative one. A test mutates the
staged files between the two and asserts the sandbox catches it, on both runtimes.

## Lifecycle placement

Redemption happens while the run is still `CLAIMED`: that is the lifecycle the control plane has enforced
since capabilities existed, and widening it for a worker's convenience would be the wrong fix. Nothing is
written at that point — the transfer is bounded and the result is in memory.

Materialisation waits for `PROVISIONING`, so no tenant byte reaches a disk before a phase deadline and a
reconciler are accountable for it.

Execution authority is re-read three times along this path: before the transfer, after it, and immediately
before the write. A worker fenced mid-delivery leaves nothing behind.

## Contract

`packages/api-contracts/source-bundle.json` holds the format version, the limits and the mount layout. Two
modules must agree on them and neither may import the other, so each has a contract test asserting its own
constants equal that file — a limit relaxed on one side alone fails the build rather than producing bundles
the other refuses at the last moment before a mount. The probe is a shell script and cannot import a Java
constant, so a third test asserts it looks for the bundle where the contract puts it.
