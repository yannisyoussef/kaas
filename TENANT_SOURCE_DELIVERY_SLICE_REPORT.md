# Tenant source delivery slice report (KAAS-18)

## 1. Executive summary

Tenant-authored bytes reach a sandbox for the first time in this repository. They arrive as **data**: mounted
read-only, hashed by a platform-owned verifier, and compared against what the command authorized. Nothing
parses, interprets, executes, sources, loads or evaluates them.

The delivery path is complete and proven end to end — sealed revision, snapshot selection, command digest,
assignment-scoped capability redemption, host-side verification against the command, `0444` staging,
read-only mount, in-sandbox re-verification, and removal — with an in-sandbox integrity check that catches
bytes altered between the host's verification and the launch.

**One requirement was not met and was not downgraded.** `noexec` is not carried onto the source mount under
the mediating runtime. Measured on both runtimes: a `local`-driver bind arrives under the baseline as an ext4
mount carrying `ro,nosuid,nodev,noexec`, and under gVisor as a 9p gofer mount carrying `ro` alone — where a
script with an exec bit **ran**. What refuses execution here instead is that the bundle format cannot express
a mode and the materialiser writes no executable bit. That is a weaker barrier than the one that was asked
for, it is reported rather than papered over, and it is why this slice does not declare tenant code execution
ready.

**Verdict: SOURCE DELIVERY BLOCKED BY BOUNDARY GAP.**

All eight CI jobs are green on this commit, and the mediated mount measurements in §29 through §33 were made
by that run rather than inherited from an earlier one. The route there is itself part of the evidence: the
first attempt failed three jobs because the staged bundle was unreadable to the sandbox uid — a defect no
local run could reproduce. See §52.

## 2. Starting commit

`fc5d55a` — *docs: adjudicate the hostile-content runtime prerequisite for inert byte delivery* (the KAAS-17
adjudication that authorized this slice).

## 3. KAAS-17 authorization boundary

KAAS-17 amended ADR-022 rather than closing it. Of its five prerequisites, four are satisfied or not
applicable to byte delivery; the fifth — untrusted content handling — split, with output handling satisfied
and **input** handling left to this slice. The amendment permits exactly one thing: delivering *inert*
tenant-authored bytes into the sandbox to be hashed and inspected. It does not permit executing them, and this
slice does not.

## 4. Scope and non-goals

**In scope.** Source bundle format and contract; SourceCapability redemption; host-side verification against
the command; staging with platform-owned modes; read-only mount; in-sandbox byte-integrity verification;
staging lifetime and reconciliation; mount-flag measurement on both runtimes.

**Explicitly out of scope, and absent from the repository.** Karate, any Karate dependency, `.feature`
parsing by anything, FeatureRevision *execution*, shell invocation with source content, tenant-supplied
binaries, tenant-selected entrypoints/images/runtimes/container settings, production secret resolution,
SecretCapability redemption, secret injection, object storage, artifacts, reports, SSE, quality gates, and
tenant-authored egress policy.

## 5. Threat model

The adversary is the content and, secondarily, a defective control plane.

| threat | answer |
|---|---|
| source bytes are executed | no code path hands them to an interpreter, shell, launcher or classloader; the workload is a fixed enum value with a fixed argument vector |
| an entry name escapes the staging root | archive entry names are never used as paths; the command's paths are checked and the resolved path is re-checked |
| one feature's source substituted for another's | per-entry content digest plus an aggregate bundle digest, both against the command |
| bytes altered between host verification and launch | re-hashed from the mounted view inside the sandbox |
| a hostile or broken peer exhausts worker memory | bounded while reading: per entry, in aggregate, and by entry count, all before the command is consulted |
| setuid material enters the sandbox | the format cannot express a mode; the materialiser writes `0444`; observed in-sandbox as a count of zero |
| the capability leaks | it lives in the delivery envelope and one local variable, and in nothing that is persisted, digested, logged or labelled |
| tenant bytes outlive the execution | staging is `AutoCloseable` and owned by a try-with-resources; a reconciler reclaims what a crash left |
| a fenced worker keeps preparing work | authority re-read before the transfer, after it, and before the write |

## 6. SourceCapability: the existing model

Assignment-scoped, minted alongside the execution authorization, stored as a hash rather than plaintext, and
bound to a run, attempt, assignment epoch and worker identity. This slice added no new capability type and
changed none of its lifecycle rules.

## 7. Redemption semantics

Redeemable only while the run is `CLAIMED`, the attempt is `CLAIMED` and unfenced, the assignment is held by
the presenting worker at the recorded epoch, and both the capability's and the authorization's windows are
open. The run row is locked first and the clock is read under the lock.

The consequence for the worker is structural: **redemption happens before `PROVISIONING` is announced**,
because announcing first would move the run out of `CLAIMED` and make every redemption fail. That is the
control plane's rule and the honest response was to place the worker's call correctly rather than widen the
window for a worker's convenience.

## 8. Capability rotation and revocation

The plaintext rotates on every delivery, which is also why a digest could not cover it. Revocation is
implicit and total: cancellation, fencing, a superseding epoch, a lease expiry or a lifecycle move all make
the capability un-redeemable at the next presentation, with no separate revocation record to keep consistent.

## 9. Internal endpoint

`POST /internal/v1/source-bundles`, on the internal security chain, authenticated as a worker, with the
capability in the `X-KaaS-Source-Capability` header. Never in the URL, where it would reach an access log.
One attempt per redemption: a redemption consumes bounded tries and must not be retried by a transport layer
that does not know that.

## 10. Transport confidentiality

The internal API is deployment-private and expected to run over TLS between the worker and the control plane;
this repository does not terminate TLS and does not claim to. What it does guarantee is that the credential is
never placed anywhere a transport can leak it independently — not a URL, not a log, not a metric, not an
exception message.

## 11. Bundle format alternatives considered

**A directory of files over HTTP, one request per feature.** Rejected: N credentials or N uses of one, N
failure modes, and no single identity to digest.

**Object storage with a pre-signed URL.** Rejected for this slice: a second credential path, a second
retention story and a second deletion story, none needed to answer this slice's question.

**A tar stream.** Rejected: tar carries modes, owners, device numbers and link targets — every one of which is
a field this design wants not to exist.

**A ZIP with the archive bytes digested.** Rejected: it would make a bundle's identity depend on the ZIP
implementation that produced it, so a JDK upgrade could change the digest of unchanged content.

## 12. Selected format

`kaas.source-bundle.v1`: a STORED (uncompressed) ZIP, written deterministically — entries sorted by path,
fixed timestamps set through the DOS field so no timezone reaches the archive, fixed compression method.

Its identity is a **canonical semantic digest** over the sorted `(logicalPath, contentDigest)` pairs plus the
format version and entry count, length-prefixed so no rearrangement of field boundaries can forge one. The
archive framing is deliberately not covered.

## 13. Bundle contract

`packages/api-contracts/source-bundle.json` holds the format version, the limits (1000 entries, 64 MiB total,
1 MiB per entry, 512-character paths) and the mount layout (`/kaas/source`, `manifest.tsv`, `files/`).

Two modules must agree and neither may import the other, so each has a contract test asserting its own
constants equal that file. A third asserts the probe — a shell script that cannot import a Java constant —
looks for the bundle where the contract puts it.

## 14. Manifest

A platform-generated TSV at the mount root, outside the tenant-controlled `files/` subtree so no logical path
can collide with it. First line: format version, bundle digest, entry count. Then one line per entry: logical
path, content digest, byte length. It is what the in-sandbox verifier checks against.

## 15. Exact feature-set binding

The runner walks the **command's** feature list, not the archive's entries, and looks each authorized logical
path up in what arrived. The set comparison runs in both directions: an entry missing and an entry extra are
different claims, and only the pair excludes substitution.

## 16. Logical path model

A logical path is relative, forward-slashed, non-empty, at most 512 characters, free of NUL and control
characters, unique both exactly and under NFC case-folding, and never a directory prefix of another. It comes
from the command, which is platform-authored and digest-covered.

## 17. Path-traversal defence

Three layers, and they defend different things:

1. **The names in the archive are never used as paths.** This is the structural one; it removes zip-slip
   rather than defending against it.
2. **The command's own paths are checked** against the rules above, because a control-plane defect must not
   become a traversal on a worker host.
3. **The resolved path is re-checked** against the files root after joining, because the string rules operate
   on a string and the filesystem operates on a resolved path.

## 18. Symlink and hardlink defence

The format has no field for a link target, so a bundle cannot request one. The materialiser has no code path
that creates a symlink or a hard link — asserted structurally. Writes use `CREATE_NEW`, so anything already at
a target path, including a symlink raced into place, fails the write rather than being followed. The
in-sandbox verifier counts entries that are neither regular files nor directories and reports zero.

## 19. Per-file limit

1 MiB, enforced **while reading** with `readNBytes(limit + 1)` rather than after buffering, and again against
the matched entry. Deliberately looser than the 512 KiB database column constraint: this is the delivery
boundary declining to hand over what the runner would refuse, not a repetition of that check.

## 20. Aggregate limit

64 MiB, accumulated while reading — before the command is consulted — and again while matching. A per-entry
bound alone does not stop aggregate exhaustion across many legal entries, which is exactly how a peer would
get there.

## 21. Streaming limit

`ControlPlaneClient.redeemSourceBundle` bounds what actually arrives rather than trusting `Content-Length`: a
peer can send a wrong length, no length, or more than it declared, and the only number that bounds this host's
memory is the one counted locally.

## 22. Exact-byte preservation

Asserted with content that would not survive normalisation, line-ending conversion or re-encoding: CRLF and LF
in one file, tabs, quotes, backslashes, an emoji, a combining accent, a non-BMP character and an RTL override.
Verified through the whole path and re-hashed from the mounted view, on both runtimes.

## 23. Aggregate digest

Computed independently by the control plane and the runner from one written rule, because one implementation
agreeing with itself proves nothing. Order-insensitive by sorting, content-sensitive by construction, and
length-prefixed so field boundaries cannot be forged.

This slice found three call sites disagreeing about whether the `sha256:` prefix was present — one repository
returned it raw, another prefixed, and the serializer added one. Unified on the prefixed form with a record
invariant that refuses anything else, so the disagreement cannot recur silently.

## 24. Host-side verification

Before anything is written: path rules, entry count, exact set match, per-entry digest, per-entry size,
aggregate size, and the aggregate bundle digest — all against the command, never against what the response
says about itself. A refusal carries a category and never a logical path or a byte of source.

## 25. Staging architecture

An opaque `kaas-source-<uuid>` directory under the operator-configured staging root. The name carries no
project, feature or path, because a directory listing on a shared host is readable by anyone who can list it.
Files are `0444` regular files; the bundle's directories are `0755`; a staging root this platform creates is
`0700`. Modes are platform-owned: tenant source carries bytes, not permissions.

The split between a private root and a readable bundle is a correction CI forced, and it is worth stating
plainly because the first version looked stricter and was simply broken. Files were `0400` and directories
`0700`, owned by the worker's user. The sandbox runs as uid 65534 and owns none of it, so **the verifier
reported a missing manifest on every mediated run** — the bundle was unreadable to its only consumer. It
passed on the development platform because Docker Desktop's virtiofs squashes ownership and hides the
difference entirely, so no local run could have found it.

What the modes are for at the boundary is unchanged: nothing can write to a staged file and nothing can
execute one. Confidentiality on the host moved to the layer that can provide it — the `0700` root, which
everything but the container must traverse, and which the container does not traverse at all because the
daemon resolves the host path as root and binds the bundle directory itself.

## 26. Staging cleanup and reconciliation

`SourceStaging` is `AutoCloseable` and the only way to obtain one is to stage a bundle, so tenant bytes live
exactly as long as the try-with-resources that owns them — on every path including failure, rather than
depending on each branch remembering a cleanup call.

`StaleSourceReconciler` reclaims what a crashed host left. It looks only in the configured root, considers
only directories carrying the platform's prefix, never follows a link out of the root, and never touches
anything younger than a 30-minute grace period — because being early destroys a live execution's source, which
is worse than leaving bytes for a few more minutes. It runs **on the execution path**, not from a timer, so it
cannot rot unnoticed behind a scheduler nobody starts in production.

## 27. Mount architecture

A read-only bind. Both sides are platform-owned: the host side is the opaque staging directory, the container
side is the fixed constant `/kaas/source`. No tenant byte contributes to either.

## 28. Read-only evidence

Enforced and observed on both runtimes, in both forms: the mount reports `ro`, **and** a write into it is
actually refused. Either alone would be weaker — a mount can report a flag it does not honour, and a write can
fail for an unrelated reason.

## 29. `noexec` evidence — THE GAP

| | baseline (`runc`) | mediating (`runsc`) |
|---|---|---|
| filesystem | `ext4` | `9p` (gofer) |
| options carried | `ro,nosuid,nodev,noexec` | `ro` |
| an executable script in the mount | refused | **executed** |

Confirmed on the final commit, from inside a mediated sandbox: the mount arrives as
`ro,trans=fd,...,directfs` — a gofer-backed 9p mount carrying `ro` alone, with `noexec`, `nosuid` and `nodev`
all requested and all absent (§52).

A second round established that gVisor honours `noexec` and `nosuid` on **tmpfs** by default — the no-flags
control behaved identically, so the flags were not what produced it — and that tmpfs cannot be populated from
the host. There is no configuration of this mount, on this runtime, that carries `noexec` today.

Execution is refused by the other barrier: the format cannot express a mode and the materialiser writes
`0444`. The strong-runtime gate observes the outcome (`source_exec_refused=true`) and separately records
`source_mount_noexec=false`, asserted in the direction each is actually true — so a future runtime release
that closes the gap fails the gate and forces re-adjudication rather than silently improving under an
unchanged claim.

## 30. `nosuid` evidence

Carried under the baseline, not under the mediating runtime. **Moot by construction** rather than by mount
option: the format has no mode field and the materialiser sets no setuid or setgid bit. Observed in-sandbox as
`source_setuid_files=0` over the real mounted bundle.

## 31. `nodev` evidence

Same shape. The format cannot express a device node and the materialiser creates only regular files and
platform-owned directories. Observed in-sandbox as `source_irregular_entries=0`.

## 32. NO_SETUID_BINARIES with a bundle mounted

KAAS-17 made `NO_SETUID_BINARIES` one of two controls compensating for the `NoNewPrivs` observation gVisor
cannot provide. Source delivery is the first thing that puts platform-external bytes inside that boundary, so
the compensation is re-observed with a bundle actually mounted rather than argued to be unaffected — under
both runtimes.

## 33. Capability bounding-set evidence

Unchanged by this slice and re-observed under it: `CAPABILITIES_DROPPED` and the rest of the mandatory control
set pass with a source mount present, in the same assessment the gate has run since KAAS-15.

## 34. gVisor integration

The mediated profile carries the source mount and nothing else changes: same image, same probe enum, same
argument vector, same control set. The launcher refuses a request naming a profile version it does not hold,
so a command authorized for one runtime cannot be served by a launcher configured for the other.

## 35. DENY_ALL integration

The default and the one every source-delivery test runs under. A bundle changes nothing about the network
posture: the sandbox has no network, and source delivery happens on the host before the container exists.

## 36. ALLOWLIST integration

Unchanged. Source is staged and mounted identically; the egress proxy path is orthogonal and is not touched by
delivery. An allowlist execution with a bundle mounted uses the same launcher derived the same way.

## 37. Mounted-view verification

The authoritative check. The runner's host-side verification describes bytes at a moment that has passed; the
in-sandbox verifier recomputes every digest from the files the sandbox actually sees.

Proven with a second-axis test on both runtimes: the staged files are mutated **after** the host's
verification and **before** the launch, and the sandbox reports `workload_outcome=FAILED` with
`source_entry_mismatches=1`. Nothing in production can perform that mutation — staging is created, written and
mounted within one call — which is precisely why it takes a test to open the window.

## 38. Platform-owned verifier

A branch of the repository-controlled probe script, selected from a server-side enum with a fixed argument
vector. It runs `sha256sum` and `wc -c`. There is no parser, no syntax check, no include resolution and no
evaluation of any kind — not because those would be hard, but because this slice delivers source as data.

It emits diagnostics and exactly one authoritative bit: `workload_outcome` is `PASSED` or `FAILED`. No tenant
filename and no source byte appears in either.

## 39. Proof that tenant bytes never execute

Four independent arguments, three of them structural:

1. **Nothing can express it.** `SandboxLaunchRequest` has three fields — probe, profile version, correlation
   id. No image, entrypoint, command, mount, capability, device, network mode, user or privileged flag. A
   caller cannot request a container shaped by tenant input because there is nowhere to put the request.
2. **The workload is a fixed enum.** Every probe's argument vector is a server-side constant matching
   `[a-z0-9]+`; no value is derived from an input. The launcher constructs no shell command.
3. **The bytes are never handed anywhere.** `ValidatedCommand` carries identities and digests and no source
   content, so the loop has nothing to pass. The bundle's contents reach exactly one consumer: a file write.
4. **Observed.** Source containing `touch /tmp/kaas-owned` in five syntaxes — shebang, plain command, `$(…)`,
   backticks, a Java `exec` call and a Karate `read()` directive — was delivered, mounted and hashed; the file
   was not created, on both runtimes, and no observation carried any of the text.

## 40. Continuous authority integration

Authority is re-read three times on this path: before the transfer, after it, and immediately before the
write. A worker fenced mid-delivery spends no redemption it cannot use and leaves no bytes on a host it no
longer serves. Each check is proven by a mutation that removes it and a test that goes red.

## 41. Cancellation and fencing

Cancellation, fencing, a superseding epoch and a lease expiry all make the capability un-redeemable at the
control plane and all end authority at the worker. The two are independent mechanisms answering the same
event, which is deliberate: a control-plane refusal and a worker's own decision to stop must not depend on
each other.

## 42. Result semantics

A refused bundle produces an infrastructure failure carrying the refusal category and no tenant content. It is
reported through the infrastructure-failure endpoint rather than returned quietly, because by then a phase
deadline may be held against the run. A verification failure inside the sandbox produces a `FAILED` test
outcome with a `SUCCEEDED` infrastructure outcome: the platform worked and the content did not match.

## 43. Output and log leakage

Asserted, not assumed. A sentinel unique to the run is placed in the source, and the result document, the
report detail and every observation are checked not to contain it. Refusal messages name the rule, never the
value. No source snippet is logged for convenience anywhere.

## 44. Structural boundaries

- `SourceBundle` has only private constructors and exactly one non-private factory, `verified` — asserted by
  reflection, so no second route to a bundle can appear silently.
- No Docker type crosses the launcher API.
- The runner module is build-guarded against depending on the control plane, which is why the bundle contract
  is duplicated and contract-tested rather than imported.

## 45. Security review

Twelve independent review passes over the delivery path, the capability path, the staging path, the mount
path, the probe, the contract and the CI gate. The material findings and their resolutions:

- **Three disagreeing digest forms** (P1). `SnapshotFeature.sourceDigest` was raw in one repository and
  prefixed in another, and the serializer added a prefix to whichever it got. Unified on the prefixed form
  with a record invariant; the serializer stopped re-prefixing; the command digest strips.
- **Redemption placed after `PROVISIONING`** (P1). A capability is redeemable only while `CLAIMED`, so every
  redemption would have failed. Split into redemption before and materialisation after.
- **Entry names were briefly authoritative** in the first draft of the reader (P0, pre-commit). Replaced by
  walking the command's list.
- **No per-entry ceiling on the control-plane side** (P2). The database constrains the column; the delivery
  boundary did not. Added, with a test that distinguishes it from the aggregate ceiling.
- **`SandboxTestSupport` package visibility** blocked the mount suite; the suite was moved into the sandbox
  package where it belongs rather than the helper being widened.
- **The staging tree was unreadable by the sandbox uid** (P0, found by CI rather than by review — the review
  passes read the mode as strict and correct, and every local run agreed with them). §25 and §52.

## 46. Mutation evidence

24 mutations (S01–S24) against the delivery, staging, mount, loop, reconciler and control-plane paths.
**23 killed, 1 survivor, documented.**

The first run killed 9 of 24. Every survivor was a real gap and each was closed with a test rather than
argued away:

| survivor | why it survived | what closed it |
|---|---|---|
| S04 path rules dropped from `verified()` | the rules were only ever tested by calling the rule method directly | a test driving the production entry point |
| S06 bounded read → `readAllBytes` | the next line still rejects the entry, so no behavioural test can see it | a structural assertion on the reader |
| S07 aggregate bound in the reader | the later accounting caught every case the suite had | an archive of legal entries with an empty authorized set, so only the reader can refuse |
| S08 entry-count bound in the reader | same | same shape, for count |
| S09 duplicate entry name | the last copy silently won and the digests still matched | a hand-renamed archive that repeats a name, which `ZipOutputStream` refuses to write |
| S11 staged file mode made writable | the file mode had no test at all | direct assertion of `0444` and `0700` |
| S12 `CREATE_NEW` → `CREATE` | the hazard is a race no test can open | a structural assertion, with the reason stated |
| S14 `close()` made a no-op | staging removal was only ever observed end to end | a direct test |
| S17–S20 loop refusals | the pipeline can only produce the states a healthy control plane produces | package-private seam plus a local HTTP server returning 403 |
| S23 reconciler symlink guard | the test aged the link's *target*, because `setLastModifiedTime` follows links | the attribute view with `NOFOLLOW_LINKS`, and the age read back before asserting |
| S24 per-entry ceiling (control plane) | the existing test's entries also broke the aggregate ceiling | a single entry over the per-entry bound and well under the aggregate one |

**The documented survivor.** S13 removes the resolved-path re-check inside the materialiser. It cannot be
killed because it cannot be reached: `verified()` is the only way to obtain a bundle and it refuses every
unsafe path shape first. Rather than assert the guard, the suite asserts the *reachability claim* — every
`SourceBundle` constructor is private and `verified` is the only factory — so if a second construction route
is ever added, that test fails and the guard stops being defence in depth and becomes the only defence.

## 47. Harness validation

The mutation harness is itself checked, because two earlier slices were misled by one that was not.

It refuses to report anything unless it can prove, in order: the anchor exists exactly once in the file; the
file on disk actually changed; the test task re-executed (`cleanTest`, and result XML newer than the edit);
and a verdict parsed from JUnit XML rather than grepped from build chatter. It restores the tree in a
`finally` **and on SIGTERM/SIGINT/SIGHUP** — added after an interrupted run left two mutations in the working
tree, where the next thing to read the file would have treated a mutant as the original.

Validated against three known outcomes before use: a non-existent anchor reported `HARNESS_FAILURE
ANCHOR_ABSENT`; a real limit relaxed reported `KILLED` naming the test; a reworded comment reported
`SURVIVED`. A harness that cannot distinguish those three is not evidence.

Two harness defects were caught by these checks during the run and are recorded rather than hidden: a battery
scoped to the whole runner module was contaminated by an unrelated local Docker flake, and one scoped to the
pipeline could not see the unit tests that actually kill the loop mutations. Both were re-scoped and re-run.

## 48. QE evidence

- `SourceBundleTests` — 13 tests: acceptance, extra/missing/substituted entries, the aggregate digest, every
  unsafe path shape, the path rules through the production entry point, the reading bounds, duplicate names.
- `SourceStagingTests` — 5 tests: file and directory modes, removal on close, failure leaving nothing, the
  write guard, and the reachability argument for the resolved-path check.
- `StaleSourceReconcilerTests` — 5 tests: stale reclaimed, live retained, unrelated retained, symlinks not
  followed, absent root harmless.
- `ExecutionLoopSourceTests` — 4 tests: authority before the transfer, a missing capability, a declined
  capability, authority before the write, each with an anti-vacuity counterpart.
- `SourceBundleContractTests` / `SourceBundleContractTest` — both sides bound to the shared contract, plus the
  probe.
- `SourceMountTests` — 5 tests, baseline runtime, observed from inside the sandbox.
- `StrongRuntimeSourceDeliveryTests` — 5 tests, mediating runtime, in the mandatory gate.
- `SyntheticExecutionPipelineTests` — end-to-end delivery with hostile and awkward content, plus the
  immutability of a sealed revision.

## 49. CI design

The strong-runtime gate gains the source-delivery suite, named explicitly rather than by a glob, so a renamed
class cannot drop out of a mandatory gate while the task still reports success. The gate:

- asserts at least 12 executed tests and **zero skips** — a suite that skipped itself because the runtime was
  absent is exactly the outcome the job exists to make impossible;
- asserts each required suite by name;
- reads back an evidence file the suite writes, and fails on `source_verification`, `source_mount_ro`,
  `source_write_refused`, `source_exec_refused`, `source_setuid_files`, `source_irregular_entries` **and** on
  `source_mount_noexec=false` — the gap recorded as a fact, so closing it forces re-adjudication;
- asserts `stale_source_dirs=0`, then `containers=0`, `networks=0`, `runsc_processes=0`.

The sentry leak check is unchanged and still proves, before it is relied on, that it can see a sentry that
exists and the absence of one.

## 50. Files changed

New: `packages/api-contracts/source-bundle.json`; `SourceBundle`, `SourceBundleContract`,
`SourceBundleRejected`, `SourceStaging`, `StaleSourceReconciler`; six test suites; ADR-030;
`docs/security/tenant-source-delivery.md`; `docs/architecture/inert-source-delivery.md`.

Modified: `ExecutionLoop`, `ControlPlaneClient`, `ValidatedCommand`, `CommandValidator`,
`DockerSandboxLauncher`, `SandboxLauncher`, `SandboxSecurityProfile`, `SyntheticProbe`, `probe.sh`;
`SourceBundlePolicy`, `SourceCapabilityService`, `ExecutionCommandPolicy`, `SnapshotFeature`,
`JdbcExecutionAuthorizationRepository`; the runner build script; the CI workflow; `IMPLEMENTATION_STATUS.md`.

## 51. Local verification

Full `cleanTest build` on Java 25 / Gradle 9.7.1 with PostgreSQL and RabbitMQ Testcontainers, plus the web,
contracts, audit and whitespace gates. **736 tests, 0 failures, 0 skips** — 326 in `apps/api`, 255 in
`services/runner`, 116 in `services/egress-proxy`, 39 in `tests/pipeline`.

One local-only observation, recorded because it is a real thing a reader will hit: under Docker Desktop the
full runner suite occasionally fails `theGateFailsClosedAndReportsEveryMandatoryControl` and takes the Gradle
test worker with it (`java.io.EOFException`). It passes in isolation and passes in CI; it is daemon contention
on the development platform, not a code defect, and it is why mutation runs are scoped to the suites that
should kill each mutation rather than to the whole module.

**A green local build proves nothing about the mediating runtime.** Docker Desktop provides no supported way
to install a runtime into its embedded VM. Only the gate does.

## 52. GitHub Actions verification

**Run 34007486485 on `a03ea8e`: five jobs green, three failed — and the failure was real.**

`hostile-execution-gate`, `synthetic-execution-pipeline` and `strong-runtime-gate` all failed for one cause:
every source-delivery test reported `source_manifest=missing`. The staging tree was `0400` files under `0700`
directories owned by the worker's user, and the sandbox runs as uid 65534, so **the bundle was unreadable to
the only consumer it has.** Nothing was delivered on any of the three jobs.

It passed locally, on the full suite and on every mutation run, because Docker Desktop's virtiofs squashes
ownership and erases the difference. No local run could have found it. This is the clearest possible instance
of the rule that a green local build proves nothing about the mediated runtime — and it applies to the
baseline one here too.

Fixed by splitting the two jobs the mode was doing: the bundle is readable so the sandbox can read it, and
confidentiality moved to a `0700` staging root, which is the layer that can provide it. §25 has the detail.
`SourceStagingTests` now asserts `OTHERS_READ` on files and `OTHERS_EXECUTE` on directories, so the property
CI caught is checkable everywhere rather than only on Linux.

**Run 34032963361 on `a6f33ef`: all eight jobs green,** and this is the first run in which
`StrongRuntimeSourceDeliveryTests` completed a mediated delivery at all — 12 executed, 0 skipped. What the
sandbox reported, read back by the gate rather than summarised by the test:

```
source_verification=VALID
source_mount_options=ro,trans=fd,rfdno=5,wfdno=5,aname=/,dfltuid=4294967294,dfltgid=4294967294,
                     dcache=1000,cache=remote_revalidating,disable_fifo_open,directfs
source_mount_ro=true
source_mount_noexec=false
source_mount_nosuid=false
source_mount_nodev=false
source_write_refused=true
source_exec_refused=true
source_setuid_files=0
source_irregular_entries=0
source_entries_verified=1
source_entry_mismatches=0
stale_source_dirs=0
containers=0 networks=0 runsc_processes=0
```

That options string is the gap in its rawest form: a 9p gofer mount (`trans=fd`, `directfs`) carrying `ro`
and nothing else. `noexec`, `nosuid` and `nodev` were requested and are absent. Execution is nonetheless
refused — `source_exec_refused=true` — by the barrier that does hold: the format cannot express a mode and the
materialiser writes no executable bit.

Every measurement in §29 through §33 is therefore confirmed on this commit rather than inherited from an
earlier one.

## 53. Required-check governance

The source-delivery suite is in a job with no `if:` and no `continue-on-error`, and the suite fails rather
than skips when the runtime is absent. Its evidence step fails when the evidence file is missing, so a job
that somehow passed without observing a mount cannot report green.

## 54. Residual risks

1. **`noexec` absent on the mediated mount.** Accepted for inert bytes; blocking for execution. §29.
2. **A gofer-backed 9p mount is a larger surface than an overlay.** Accepted: it carries no tenant-controlled
   metadata, only paths and bytes the platform chose.
3. **The staging root is host-local.** A host that loses power holds tenant bytes until a later execution's
   reconciler reclaims them, bounded by the grace period. There is no cross-host reconciler.
4. **The internal API's transport confidentiality is a deployment property**, not a repository one.
5. **The in-sandbox verifier is a shell script.** Its correctness is asserted by the second-axis test that
   makes it fail on altered bytes; a verifier that always passed would fail that test.

## 55. Runtime-pin attestation gap

Unchanged and still open from KAAS-15: the signed attestation states which runtime produced the evidence, but
nothing pins the runtime binary's identity into the signed payload. It remains out of scope here and remains a
prerequisite for tenant code execution.

## 56. Exact blockers before tenant code execution

1. `noexec` enforced on the source mount under the mediating runtime, measured — or an accepted, documented
   substitute of equivalent strength.
2. The runtime-pin attestation gap closed.
3. ADR-022's execution adjudication, which this slice does not perform and does not pre-empt.
4. A decision about what actually interprets the source, which does not exist in this repository in any form.

## 57. Requirements for an execution-readiness adjudication

It will need: the two gaps above resolved; an engine whose input surface is adjudicated on its own terms
(Karate's `read()`, `classpath:`, Java interop and JS evaluation are each a separate question); a decision on
what a workload may do with its filesystem, its network and its time once it is a program rather than a
hash; and evidence produced by the deployment's own runtime rather than inherited from this slice's.

## 58. Recommended next slice

**Close the mount gap, or prove it cannot be closed.** It is the smallest well-defined piece of work, it is
the one everything else waits behind, and its outcome changes what the next adjudication can even consider.
Delivering more content, or delivering it faster, adds nothing while the barrier that was asked for is absent.

## 59. Final verdict

**SOURCE DELIVERY BLOCKED BY BOUNDARY GAP**

The delivery path is complete, measured and proven end to end on both runtimes. `ro` is enforced and observed.
`nosuid` and `nodev` are moot by construction. `noexec` is **not** enforced under the mediating runtime, and
that requirement was not downgraded to fit the code that was otherwise finished.

Tenant code execution is not ready and is not declared ready.
