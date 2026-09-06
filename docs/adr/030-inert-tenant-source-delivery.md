# ADR-030: Inert tenant source delivery

**Status: ACCEPTED.** Permits tenant-authored bytes inside the sandbox as data. Does not close ADR-022, and
does not approve executing them.

## Context

ADR-022 refused arbitrary tenant code execution and named five prerequisites. KAAS-17 amended it: four are
satisfied or not applicable, and the fifth — untrusted content handling — split, with output handling
satisfied and *input* handling left to the slice that first carries tenant bytes across the boundary. That is
this one.

Nothing before this slice put a byte the platform did not author inside a sandbox. Every probe, image, script
and argument vector was repository-controlled. That is a strong position and it is also a position from which
the product cannot ship: a test platform whose users cannot supply their tests is not a test platform.

The narrowest step that makes progress is to deliver the bytes and refuse to interpret them.

## Decision

### Tenant source enters the sandbox as data, and only as data

Bytes may be stored, transported, mounted, read, hashed and compared. They may not be parsed as a feature
file, executed, sourced by a shell, passed to an interpreter, loaded as classes, or used as configuration, as
a launcher argument, as a container setting, as a filesystem path outside the bundle, or as a command.

This is enforced by what exists rather than by what is checked. The workload that runs over a mounted bundle
is the repository's own verifier, it is selected from a server-side enum, and its argument vector is fixed.
There is no code path that takes a byte of tenant source and puts it anywhere a program could reach it.

### The bundle format cannot express anything but a path and bytes

No mode, no owner, no link, no device, no timestamp with meaning. The classic archive attacks are therefore
not defended against — they are unrepresentable. The runner does not walk the delivered archive and
materialise what it finds; it walks the **command's** feature list, which is platform-authored and covered by
the command digest, and looks each authorized path up in what arrived. An entry the command did not authorize
is a refusal rather than a file.

### The capability is bearer authority and lives in the envelope

The plaintext SourceCapability token is never inside the ExecutionCommand, never inside the command digest,
never in PostgreSQL plaintext, the broker, the outbox, a container label, a filesystem name, a metric, a log
line or an exception. It rotates on every delivery, which is also why a digest could not cover it.

### Verification happens twice, in two places, for two different reasons

The runner verifies the delivered bundle against the command before anything is written: that is what stops a
control-plane defect becoming source substitution. The platform's verifier then re-hashes the bytes **from
inside the sandbox**, over the mount the workload actually sees: that is what covers the window between the
host's check and the launch, which no host-side hash can describe.

### The delivery boundary is measured, and it is not uniform

Read-only is enforced under both runtimes, observed as a mount option and as a refused write.

`noexec` is **not** carried onto the mount under the mediating runtime. A `local`-driver bind arrives over the
gofer as a 9p mount carrying `ro` and nothing else, and a measurement under the baseline runtime carrying the
full flag set does not transfer. What refuses execution here is the other barrier: the materialiser writes
every file `0444` and the format cannot express a mode at all.

`nosuid` and `nodev` are moot by construction rather than by mount option, for the same reason.

## Consequences

- Tenant bytes reach a sandbox for the first time. They are hashed, not run.
- **ADR-022 stays open.** Executing tenant content is a separate decision with its own adjudication, and the
  `noexec` gap above is one of the things that adjudication will have to resolve rather than inherit.
- The runtime-pin attestation gap recorded in KAAS-15 also remains open and is a prerequisite for tenant code
  execution.
- Karate is still absent from this repository, and no `.feature` file is parsed by anything.

## Alternatives considered

**Deliver source through an object store.** Rejected for this slice: it adds a second credential path, a
second retention story and a second deletion story, none of which are needed to answer the question this slice
exists to answer.

**Extract the archive by its own entry names.** Rejected. It would make the delivered archive authoritative
over the filesystem layout, which is precisely the authority this design withholds from it.

**Digest the archive bytes rather than the semantic content.** Rejected: it would make the identity of a
bundle depend on the ZIP implementation that produced it, so a JDK upgrade could change the digest of content
that had not changed.

**Wait for `noexec` before delivering anything.** Rejected, but stated rather than assumed: the gap is
reported, the compensating barrier is observed in CI, and the requirement is not downgraded — tenant code
execution stays unapproved.
