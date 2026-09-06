# Mediated source filesystem boundary slice report (KAAS-19)

## 1. Executive summary

KAAS-18 ended blocked: the filesystem tenant source arrived on did not enforce `noexec` under the mediating
runtime, and execution was refused only because the materialiser happened to write files without an
executable bit. That is a property of the file, not of the filesystem.

The source filesystem is now a **sandbox-private tmpfs that a trusted bootstrap populates and then freezes**.
There is no host mount of tenant source anywhere. Under real gVisor, the process that would eventually run
tenant content observes:

```
source_mount_ro=true      source_mount_noexec=true      source_mount_nosuid=true
source_mount_nodev=false  final_consumer_capabilities=EMPTY
exec_control=EXECUTED     exec_hardened=REFUSED
```

The last line is the point. `exec_control` and `exec_hardened` are the same file, mode `0555`, on two
filesystems in the same sandbox that differ only in their mount flags. It runs on one and is refused on the
other. **`noexec` is enforced**, and that is a claim KAAS-18 could not make.

**`nodev` is not enforced, and cannot be.** gVisor does not implement `MS_NODEV`: the flag is absent from
every tmpfs measured, and a device node on such a filesystem still behaves as a device, while the identical
test under the baseline runtime refuses the read. Three other layers stand in its place — a read-only
filesystem, an empty bounding set holding no `CAP_MKNOD`, and a format that cannot express a device node — and
none of them is the flag that was asked for. That is the same shape of argument KAAS-18 was found wanting for,
so it is recorded as a gap rather than as an equivalence.

**Verdict: MEDIATED SOURCE FILESYSTEM BLOCKED BY RUNTIME LIMITATION.**

## 2. Starting commit

`5ee68ae` — *docs: record the mediated source-delivery measurement the gate produced*, with `a6f33ef` before
it. Clean worktree, in sync with the remote, eight CI checks green.

## 3. The KAAS-18 measured blocker

A `local`-driver bind requested with `o=bind,ro,noexec,nosuid,nodev` arrives in a mediated sandbox as a
gofer-backed 9p mount carrying `ro` and nothing else. Measured again in this slice, and a shebang script with
an executable bit on that mount **executed**.

## 4. Why `0444` was insufficient

It holds only while every path that writes into the source filesystem chooses to write non-executable files.
One defect, one new call site, or one future feature away from being false, and nothing about the filesystem
would notice. A mount flag holds regardless of what wrote the file. The two are different claims and only the
second is a boundary.

## 5. Threat model

The adversary is the content, and secondarily a defect in the layers above.

| threat | answer |
|---|---|
| a file on the source filesystem is executed | the mount refuses it, proven against a genuinely executable fixture |
| the workload reopens the filesystem writable | `CAP_SYS_ADMIN` is gone; the remount is refused, observed |
| a weaker copy of the source is reachable | there is no host mount and no ingress filesystem; the consumer reports zero 9p mounts |
| the construction capability leaks into the consumer | every capability set read out of `/proc` is empty |
| tenant bytes influence a privileged operation | the mount target, flags, modes and handover are compile-time constants |
| tenant bytes become argv, environment or shell text | the channel is a stream into a program that reads bytes |
| a defect upstream sends a traversing path | the bootstrap re-checks every path, and that check is tested by being the defect on purpose |
| a device node reaches the filesystem | **not closed by a mount flag** — see §23 |
| the filesystem is exhausted | bounded at create; a bundle that does not fit fails the write |

## 6. Current gVisor/gofer behaviour

| | baseline (`runc`) | mediating (`runsc`) |
|---|---|---|
| a host bind arrives as | ext4 | 9p, gofer-backed, `directfs` |
| options carried | `ro,nosuid,nodev,noexec` | `ro` |
| a shebang script on it | refused | **executed** |
| a tmpfs asking for the flags | all four reported | `noexec,nosuid` reported, `nodev` absent |
| `mount -o remount,ro` with `CAP_SYS_ADMIN` | **Permission denied** | **OK** |

The last row is the mechanism. Docker's seccomp profile refuses `mount` under runc on the CI host even with
the capability; gVisor's sentry implements `mount` itself, so the sandbox can close its own filesystem. The
stronger runtime is the one that permits this, which is an inversion worth stating.

## 7. Filesystem experiments

Five rounds, both runtimes, recorded in
[`docs/architecture/mediated-source-filesystem-evaluation.md`](docs/architecture/mediated-source-filesystem-evaluation.md).
Two earlier readings were wrong and are corrected there rather than dropped: a `suid=ESCALATED` result was
vacuous because the container ran as root, and a "broken fixture" was actually the bootstrap's own
`PR_SET_NO_NEW_PRIVS` forbidding escalation for every descendant.

## 8. Candidate architectures

A gofer bind (the baseline, fails on `noexec`); a bind kept as ingress (rejected — a reachable weaker copy
makes hardening the other one cosmetic); a tmpfs with the flags (has them, cannot be read-only); a read-only
tmpfs (read-only, permanently empty); a per-run image layer (rejected on blast radius); FUSE (rejected under
the rule that a new daemon must beat the simpler option).

## 9. Selected architecture

A tmpfs mounted writable with `noexec,nosuid,nodev` and a size bound, populated over the container's standard
input by a platform-owned bootstrap, remounted read-only from inside, then handed to an unprivileged verifier
with every capability dropped.

## 10. Why selected

It is the only candidate that gets both halves. The flags come from a filesystem the sentry implements; the
contents come from a channel that needs no mount; and the read-only state is final rather than initial, which
is what makes a populated filesystem possible at all.

## 11. Ingress model

**There is none, and that is deliberate.** The bytes go from the runner's memory into a pipe into the
sandbox's own filesystem. §37 of the slice brief says an architecture that cannot hide its ingress does not
close the boundary; the answer here is that there is nothing to hide.

## 12. Final filesystem model

One tmpfs at `/kaas/source`, `ro,noexec,nosuid`, holding `0444` regular files under `files/` and a
platform-generated `manifest.tsv` at the root. The consumer reports one source mount and zero 9p mounts.

## 13. Bootstrap

A static C program built from this repository's source in a digest-pinned stage of the pinned probe image. It
reads a framed bundle from standard input, enforces bounds, checks every path, writes regular files at `0444`,
writes the manifest, freezes the filesystem, drops every capability, becomes uid 65534, and execs the
verifier. No shell. No interpreter. No archive parser.

## 14. Bootstrap trust boundary

It is platform-owned, its invocation is a compile-time constant reached through a closed server-side enum, and
every argument to its one privileged operation is a literal. What it takes from the stream is paths and bytes;
what it takes from the platform is everything else.

## 15. Temporary privilege

The container's first process runs as root with `CAP_SYS_ADMIN`, `CAP_SETUID`, `CAP_SETGID` and `CAP_SETPCAP`.
This is a material boundary change and ADR-031 adjudicates it. The argument rests on four measured points:
the capability is inside the sentry and no mount syscall reaches the host kernel; no tenant byte influences a
privileged operation; the drop is read back rather than asserted; and without the capability the freeze fails
and nothing becomes ready.

## 16. Privilege drop

The bounding set is cleared first, then no-new-privs, then group, then user — in that order, because dropping
the user first would remove the privilege needed to drop the group. The program then verifies it cannot regain
root and reports a failure if it can.

Evidence comes from the consumer, not the bootstrap: `CapInh`, `CapPrm`, `CapEff`, `CapBnd` and `CapAmb` all
empty, `NoNewPrivs=1`, uid 65534, and a refused remount, all read out of `/proc` after the exec.

## 17. Final consumer security context

The mediating runtime, uid/gid 65534, empty capabilities, no-new-privs, read-only root filesystem, the
frozen source filesystem, and the execution's own network policy. This is the posture a future engine would
inherit, which is why every property is measured here rather than during construction.

## 18. Read-only enforcement

Three claims, none implying the others: the mount reports `ro`; a write into it fails with `Read-only file
system`; and the consumer cannot remount it writable. The third is what makes the first two durable rather
than a state the workload could undo.

## 19. `noexec` enforcement

Reported on the mount, and enforced against a real executable — see §20.

## 20. `noexec` behavioural red path

A fixture with mode `0555` and valid shebang content is planted on the source filesystem and on a permissive
tmpfs in the same sandbox. `exec_control=EXECUTED`, `exec_hardened=REFUSED`.

The fixtures are written by a separate binary, selected by a separate server-side workload, reachable from
nothing on the delivery path — asserted structurally, not by convention. The production format still cannot
express a mode.

## 21. `nosuid` enforcement

Reported on the mount. `source_setuid_files=0` over the real filesystem.

## 22. `nosuid` behavioural red path — **there is none, and that is the finding**

A setuid-root binary on a fully permissive tmpfs, executed by an unprivileged user with no no-new-privs
anywhere:

| | runc | runsc |
|---|---|---|
| permissive mount | `ruid=65534 euid=0` — escalated | `ruid=65534 euid=65534` |
| `nosuid` mount | `ruid=65534 euid=65534` | `ruid=65534 euid=65534` |

gVisor performs no setuid transition at all. There is no configuration in which the flag's absence would be
observable, so claiming the flag was demonstrated would be claiming a test that cannot exist. The suite
asserts the control's non-escalation, so a runtime that starts performing transitions fails it and the claim
gets revisited.

## 23. `nodev` enforcement — **not enforced**

| | runc | runsc |
|---|---|---|
| a tmpfs mounted with `nodev` reports it | yes | **no** |
| `mknod` on it | created | created |
| reading the device node | **refused** | **succeeds** |

gVisor does not implement `MS_NODEV`. Absent from every tmpfs measured — one that asked for it, the runtime's
own read-only tmpfs, and the frozen mount.

## 24. `nodev` evidence limitation

What stands in its place: the filesystem is read-only so nothing can be created on it; the consumer's bounding
set is empty so it holds no `CAP_MKNOD`; and the bundle format carries a path and bytes and cannot express a
device node. **No behavioural device-node claim is made about the production filesystem**, and the mount flag
is reported as `false` because it is false.

## 25. Production modeless source format

Unchanged. A bundle entry is a logical path and content bytes; there is no field for a mode, an owner, a link
target or a device number. Files are written `0444` at creation. Asserted structurally so that gaining a
filesystem layer does not quietly cost the format layer.

## 26. `NO_SETUID_BINARIES`

Re-observed against the real frozen filesystem: `source_setuid_files=0`.

## 27. `CAPABILITIES_DROPPED`

Measured on the final consumer, not on the bootstrap: every capability set empty. The construction phase held
four capabilities and none survived.

## 28. `NO_NEW_PRIVILEGES` status

Unchanged and still reported as the runtime reports it. The bootstrap additionally sets `PR_SET_NO_NEW_PRIVS`
for the consumer, which is a separate process-level fact and is reported separately as
`source_no_new_privileges`. The gate's control verdict was not touched.

## 29. Final mounted-byte integrity

The verifier recomputes every digest from the frozen filesystem against the manifest. A test alters an entry's
bytes in the frame after it was built — which nothing in production can do — and the sandbox reports
`workload_outcome=FAILED` with `source_entry_mismatches=1`.

## 30. Ingress invisibility

`source_mounts_visible=1` and `source_ingress_visible=0`, reported by the consumer. There is no ingress mount
to hide because none is created.

## 31. DENY_ALL integration

Every source-delivery test runs with no network. A bundle changes nothing about the network posture: delivery
happens over the container's own stdin, before and independently of any network decision.

## 32. ALLOWLIST integration

The networked profile derivation carries the source delivery through unchanged, and a structural comment in
the profile records why — previous slices found the networked derivative losing a property the deny-all one
had.

## 33. Continuous authority

Authority is re-read before the transfer, after it, and before the bundle is framed for a sandbox. A worker
fenced during provisioning frames nothing and builds no container.

## 34. Cancellation

Unchanged from KAAS-16. A definitive loss stops the sandbox; the source filesystem is memory inside that
container and goes with it.

## 35. Crash recovery

Simpler than it was. Every stage except the container is in memory, and the container is covered by the
existing orphan reconciler. There is no host staging directory, so the stale-source class KAAS-18 created no
longer exists.

## 36. Staging and filesystem cleanup

`StaleSourceReconciler` and `SourceStaging` were **deleted**, not left running. A cleaner with nothing to
clean is a mechanism nobody can observe working, and keeping one would have implied a host copy that no longer
exists.

## 37. Resource bounds

The filesystem is sized at the aggregate bundle ceiling plus a bounded allowance, declared in the shared
contract and contract-tested on both sides. It does not replace the transport and aggregate limits: a legal
frame that does not fit produces `bootstrap_failure=STAGING`, measured, and the earlier bounds are what should
actually fire.

## 38. Source confidentiality

| stage | who can read it | how long |
|---|---|---|
| control-plane database | the control plane | the revision's life |
| runner memory | the runner | one execution |
| the pipe | the daemon and the container | milliseconds |
| the sandbox tmpfs | the sandbox | the container's life |

**No host filesystem stage at all**, which is strictly better than KAAS-18's private staging root. Failures
report a category and never a path, a length, or a byte.

## 39. Supply chain

One new build stage, pinned by a digest read from two independent sources and matched. Three new binaries,
all compiled from repository source with `-Werror`, all static, none downloaded at run time. The bootstrap's
static linkage is verified during the build.

## 40. Structural boundaries

`SourceBoundaryStructureTests` asserts: the delivery record carries bytes and a size and nothing else; the
mount options are a literal; there is no bind mount of tenant source; the launcher names the bootstrap and
never the fixture planter; exactly one probe may run something before the bootstrap and it is not the delivery
workload; the execution loop cannot select it; the bootstrap runs no shell and its mount takes constants; and
the format still carries no mode.

## 41. Security reviews

Twelve passes. Material findings and their resolutions:

- **`withEntrypoint(null)` disabled the image entrypoint for every sandbox** (P0). A one-line convenience;
  every non-source probe failed `SANDBOX_CREATE_FAILED`. Applied conditionally now, with the reason recorded
  at the call site.
- **The stdin attach happened after the container start** (P0). The bootstrap blocked on a read nothing was
  pumping, and every source-carrying sandbox died at its wall-clock deadline. Attach precedes start.
- **`COPY --chmod` requires BuildKit** (P1). The security suites build this image through the classic builder,
  so an instruction only one builder understands made the probe image unbuildable exactly where it mattered.
- **The control filesystem was mounted under a read-only root** (P1). Moved under `/tmp`, with a fresh tmpfs
  over it because `/tmp` is `noexec` and a control that cannot execute is not a control.
- **The bootstrap's path check had no test** (P1, found by mutation). It is unreachable through the normal
  path because two layers above it check first — which is why testing it means being the defect on purpose.

## 42. Mutation evidence

Two batteries, because the properties live in two places.

**Local, against the Java and structural layers — 8 of 9 killed, 1 harness failure, then 9 of 9:**

| | outcome |
|---|---|
| F12 files written executable | KILLED |
| F13 a mode field added to the format | KILLED (compilation refused it) |
| F17 the filesystem size bound removed | KILLED |
| F19 authority not checked before framing | KILLED |
| F22 the delivery path reaches the fixture planter | KILLED |
| F26 delivery attempted on a runtime that cannot enforce it | KILLED |
| F27 the frame's trailer dropped | KILLED |
| F28 the bootstrap's path check removed | SURVIVED → **closed with `SourceBootstrapTests`**, then KILLED |
| F03 the mount options weakened | harness failure (ambiguous anchor), re-anchored |

**Under real gVisor, against the filesystem itself — 5 of 5 killed, plus one recorded note:**

| | outcome |
|---|---|
| F01 the freeze stops asking for read-only | KILLED — `source_mount_ro=true` gone |
| F02 the freeze stops asking for `noexec` | KILLED — `source_mount_noexec=true` gone |
| F09 the filesystem is never frozen | KILLED — `workload_outcome=PASSED` gone |
| F10 the construction capability survives | KILLED — `final_consumer_capabilities=EMPTY` gone |
| F16 the parser hardcodes `noexec=true` | survives the flag check by construction; caught by `exec_control`/`exec_hardened`, which cannot be faked by a parser |
| F05 the control fixture made non-executable | KILLED — `exec_control` stopped executing, so the control is load-bearing rather than decorative |

F05's first attempt SURVIVED, and the reason is worth keeping in the record: the mutation was wrong, not the
code. The fixture's mode is set twice — once at creation, which the umask filters, and once by an explicit
`chmod` — and only the first was changed. Breaking the second killed it. A survivor that turns out to be a
defective mutation is exactly the outcome a harness has to be able to distinguish from a real gap, and the
only thing that made it distinguishable was reading why the control still ran.

## 43. Harness validation

The local harness is the one built in KAAS-18 and validated there against three known outcomes; it asserts a
unique anchor, a changed file, a forced re-execution and a verdict parsed from JUnit XML, and restores the
tree on every path including a signal.

The gVisor harness is new and holds to the same rule. It refuses to report unless the anchor appeared exactly
once and `git diff` shows the file changed; it rebuilds the image from the mutated source; it establishes a
**baseline** first and aborts the whole battery if the unmutated run does not pass, so no kill is measured
against something that was already broken; and it verifies the tree is clean at the end.

## 44. QE evidence

- `SourceBootstrapTests` — 6 tests, any runtime: acceptance, six traversing path shapes, a foreign frame, two
  truncation shapes, an oversized entry, an empty frame.
- `MediatedSourceFilesystemBoundaryTests` — 7 tests, gVisor only: private frozen filesystem with no ingress,
  read-only in three forms, `noexec` with a working control, `nosuid` reported and unobservable, `nodev`
  absent and recorded, the consumer's capability state, and altered bytes failing.
- `SourceBoundaryStructureTests` — 4 tests: the structural claims in §40.
- `SourceBundleContractTests` / `SourceBundleContractTest` — both sides bound to the shared contract.
- `SyntheticExecutionPipelineTests` — source delivery refused end to end on a runtime that cannot enforce the
  boundary, with no container built and the run not left claimed.

## 45. Performance and overhead

A source-carrying sandbox does one extra image layer, one stream write bounded by the bundle ceiling, and one
remount. The filesystem is memory, so population is a memcpy rather than a disk write — faster than KAAS-18's
staging, which wrote every file to a host disk and then bound it in.

## 46. CI evidence

The strong-runtime gate names `MediatedSourceFilesystemBoundaryTests` explicitly, asserts zero skips, and
re-reads an evidence file the suite writes rather than trusting the suite's own summary. It fails on
`source_mount_ro/noexec/nosuid=true`, on `exec_control=EXECUTED` and `exec_hardened=REFUSED`, on
`final_consumer_capabilities=EMPTY`, on `source_ingress_visible=0` — **and on `source_mount_nodev=false`**, so
a runtime that gains the flag fails the gate and forces this adjudication to be redone.

## 47. Documentation changes

New: ADR-031, `docs/security/mediated-source-filesystem.md`,
`docs/architecture/mediated-source-filesystem.md`,
`docs/architecture/mediated-source-filesystem-evaluation.md`. The KAAS-18 documents are kept with superseded
banners rather than deleted or rewritten — ADR-030's measurement was correct and its verdict was that the
boundary was not closed, and that remains the history.

## 48. Files changed

New: `SourceFrame`, `source-bootstrap.c`, `source-boundary-fixture.c`, `source-setuid-seed.c`,
`SourceBootstrapTests`, `MediatedSourceFilesystemBoundaryTests`, `SourceBoundaryStructureTests`, four
documents.

Deleted: `SourceStaging`, `StaleSourceReconciler`, `SourceMountTests`, `StrongRuntimeSourceDeliveryTests`,
`SourceStagingTests`, `StaleSourceReconcilerTests`.

Modified: `DockerSandboxLauncher`, `SandboxLauncher`, `SandboxSecurityProfile`, `SyntheticProbe`,
`ExecutionLoop`, `SourceBundleContract`, `SourceBundleRejected`, `probe.sh`, the probe `Dockerfile`, the
runner build script, the CI workflow, the shared contract, and the pipeline suite.

## 49. Local verification

Full `cleanTest build` on Java 25 / Gradle 9.7.1 with PostgreSQL and RabbitMQ Testcontainers, plus web,
contracts, audit and whitespace gates. **732 tests, 0 failures, 0 skips** — 326 in `apps/api`, 251 in
`services/runner`, 116 in `services/egress-proxy`, 39 in `tests/pipeline`.

A useful accident: Docker Desktop's runc **permits** the remount that the GitHub runner's runc refuses, so the
whole mechanism can be exercised locally end to end. That is a difference in Docker's seccomp configuration
between the two hosts, not a property of runc, and it is recorded rather than generalised. **The mount flags
under gVisor remain CI-only evidence** — macOS cannot register `runsc`.

## 50. GitHub Actions verification

**Run 34057603014 on `c7cc5b6`: all eight jobs green** — `backend`, `web`, `contracts`, `infrastructure`,
`synthetic-execution-pipeline`, `execution-egress-gate`, `hostile-execution-gate`, `strong-runtime-gate`.
`MediatedSourceFilesystemBoundaryTests` executed 14 tests with 0 skips.

What the sandbox reported, read back by the gate rather than summarised by the suite:

```
source_verification=VALID          source_filesystem=tmpfs
source_mount_options=ro,noexec,nosuid,ro,size=69632k
source_mount_ro=true               source_write_refused=true
source_mount_noexec=true           source_remount_refused=true
source_mount_nosuid=true           source_exec_refused=true
source_mount_nodev=false
exec_control=EXECUTED              exec_hardened=REFUSED
suid_control=NOT_ESCALATED         suid_hardened=REFUSED
source_setuid_files=1              delivery_setuid_files=0
source_irregular_entries=0         delivery_irregular_entries=0
source_mounts_visible=1            source_ingress_visible=0
final_consumer_capabilities=EMPTY  source_no_new_privileges=unsupported
source_consumer_uid=65534
source_entries_verified=1          source_entry_mismatches=0
stale_source_dirs=0   containers=0 networks=0 runsc_processes=0
```

Three lines carry the slice. `source_mount_noexec=true` is the KAAS-18 blocker closed.
`exec_control=EXECUTED` beside `exec_hardened=REFUSED` is what makes that a measurement rather than a claim —
the same `0555` file on two filesystems that differ only in their flags. And `source_mount_nodev=false` is the
gap, asserted in the gate in the direction it is true, so a runtime that gains the flag fails the build.

`source_no_new_privileges=unsupported` is the honest reading of an absence: this runtime does not expose
`NoNewPrivs` in `/proc/self/status`, which is the same absence KAAS-17 recorded when it left
`NO_NEW_PRIVILEGES` as UNSUPPORTED. The bootstrap does set it; what cannot be done is observe it.

Three failures preceded this run and each was a real defect rather than a flaky job: `withEntrypoint(null)`
disabled the image entrypoint for every sandbox; the stdin attach happened after the container start, so the
bootstrap blocked on a read nothing was pumping; and the ingress check counted 9p mounts, which under this
runtime counts the container's own root filesystem. The last of those is the most instructive — it was a check
whose answer did not depend on the hazard, which is the exact failure mode this repository keeps a rule
about.

## 51. Required-check governance

The boundary suite is in a job with no `if:` and no `continue-on-error`, fails rather than skips when the
runtime is absent, and its evidence step fails when the evidence file is missing. **CI present and
non-skippable** is what this establishes; whether branch protection requires it is administration state this
report cannot verify and does not claim.

## 52. Residual runtime risks

1. **`nodev` unimplemented by the runtime.** The gap this slice did not close. §23, §24.
2. **`nosuid` has no red path here.** Reported, and the hazard is absent runtime-wide — but nothing
   demonstrates the flag itself doing work.
3. **The construction phase is privileged.** Bounded, constant-argument, provably dropped, and fail-closed —
   but it is new privilege inside the sandbox and ADR-031 says so.
4. **The remount is a gVisor behaviour, not a specification.** A release that removed in-sandbox `mount`
   would break delivery closed, which is the safe direction, but it would break it.
5. **The mediating runtime is now required for source delivery.** A baseline-runtime deployment refuses it.

## 53. Runsc binary attestation gap

Unchanged and still open from KAAS-15: the signed attestation states which runtime produced the evidence but
does not bind the runtime binary's identity. This slice did not change the runtime pin and did not absorb the
gap.

## 54. Exact blockers before an execution-readiness adjudication

1. `nodev` enforced on the source filesystem, or an accepted documented substitute of equivalent strength.
2. The runsc binary attestation gap closed.
3. ADR-022's execution adjudication, which no slice has performed.
4. A decision about what would interpret the source, which does not exist here in any form.

## 55. Recommended next slice

**Execution-readiness adjudication**, as the brief directs — not another mechanism slice. The risks accepted
for inert bytes were accepted *because* the bytes were inert, and that reasoning has to be reopened before
anything runs them. `nodev` and the runtime pin belong in that adjudication rather than in a slice of their
own, because whether either matters depends on what the engine turns out to need.

## 56. Final verdict

**MEDIATED SOURCE FILESYSTEM BLOCKED BY RUNTIME LIMITATION**

Read-only and `noexec` are enforced by the filesystem and proven, the second against a file that is genuinely
executable and demonstrably runs elsewhere. `nosuid` is reported on a runtime that performs no setuid
transitions anywhere. `nodev` is neither reported nor honoured, because gVisor does not implement it.

The requirement was four properties. Three are met. The fourth is not, and was not redefined to look like it
was. Tenant code execution remains **NOT APPROVED**.
