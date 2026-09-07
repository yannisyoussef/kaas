# Tenant execution readiness slice report (KAAS-20)

## 1. Executive summary

Every slice before this one answered *can tenant bytes be here safely?* This one asks *can they be
interpreted?* — a different boundary, and one `noexec` does not touch.

Two things were settled by measurement rather than by reasoning.

**What Karate is.** Karate 2.1.2 was inspected as a build artifact. `Java.type(name)` resolves to
`Class.forName(name, true, contextClassLoader)` with **no allowlist, no class filter and no configuration
gate**; the Java bridge is installed unconditionally on the ordinary path; and the only filtering logic in the
engine is a JPMS accessibility *workaround* that finds an accessible method rather than refusing one. Tenant
Karate source is therefore arbitrary JVM code, and a restricted-DSL model is not available without forking the
engine. **The model is FULLY HOSTILE: containment is the boundary and the classpath is a security control.**

**What the boundary does to a JVM.** A platform-owned hostile Java workload, under the exact posture a future
engine would inherit: every capability set empty, source filesystem write/chmod/exec/remount/`mknod` all
refused, scratch space writable and non-executable, DNS and three socket destinations refused, no daemon
socket, no credential in the environment, and Java threads bounded by the PID ceiling at 49 of 200 attempted.

**One gap was closed in this slice.** Signed evidence now binds the runtime binary — version, SHA-256 and an
opaque path identity — measured from the daemon's own registration with no operator-supplied value anywhere.
Replacing `runsc` invalidates every existing attestation automatically.

**Verdict: READY FOR SECRET-FREE KARATE EXECUTION SLICE**, under the binding restrictions in §56.

## 2. Starting commit

`9cb4c86` — *docs: adjudicate the mediated source filesystem boundary*. Clean worktree, in sync, eight of
eight CI checks green.

## 3. Scope and non-goals

Adjudication, with bounded implementation permitted where it closes a gap the decision depends on. **No Karate
dependency was added to any build file.** No `.feature` parsing, no engine, no secrets, no artifacts, no SSE,
no quality gates. The jars were fetched into a scratch directory and read with `javap`; that is evaluation, and
the repository's dependency graph is unchanged.

## 4. Why execution differs from byte delivery

`noexec` stops the kernel executing a file from `/kaas/source`. It says nothing about an interpreter reading
those bytes and acting on them — and the interpreter is the product. KAAS-19's boundary answers a question
about the filesystem; this one is a question about semantics.

## 5. Current containment stack

Mediated runtime with no fallback and a read-back runtime check; a frozen sandbox-private source filesystem
with no host mount; empty capabilities and no-new-privs on the consumer; deny-all or proxy-only networking;
memory, swap, CPU, PID, wall-clock and output ceilings; continuous execution authority with prompt revocation;
and per-execution containers that take everything with them.

## 6. Readiness criteria

The thirteen in the brief's §95, adjudicated in `docs/security/execution-readiness-matrix.md`. Ten are
ENFORCED and measured, two are ACCEPTED with tested compensating controls, and one — the `read()`/`call()`
path containment — is a KAAS-21 requirement rather than a present property, and is named as such.

## 7. Runtime implementation identity gap

Everything v4 signed described the runtime *family*: `GVISOR`, a profile version, an operator label, a hash of
the daemon's instance id. All of it survives replacing the `runsc` binary. Evidence gathered against one build
could authorize execution against another — accepted twice, on the grounds that the runtime was not the thing
under attack. Once tenant code executes, it is.

## 8. Runtime identity solution

`RuntimeImplementation` measures the binary the daemon registered: name, version (by executing it), SHA-256 of
the file, and an opaque hash covering the configured and resolved paths together. The path comes from
`docker info`'s runtime registration — not `PATH`, not an operator parameter — and symlinks are resolved so
the digest is of the file that will run.

Docker registers its default runtime as a bare name rather than a path. A bare registration is resolved the
way the daemon itself would, and the report says plainly that this is a weaker binding than an absolute
registration, which is what this repository's own gVisor registration uses.

The producer has no option that sets any of these. An operator who could type a digest would be attesting to a
claim rather than to a binary.

## 9. Attestation evolution

Schema **v5**, domain separator `KAAS_SANDBOX_SECURITY_ATTESTATION_V5`, four new signed fields. All three
independent implementations moved together — the runner's producer, the control plane's verifier, and the
Python vector generator written from the contract in another language — and the committed vectors are
regenerated from the third.

New negative vectors: each implementation field tampered individually, plus `superseded-v4` — a genuine v4
document, signed by a trusted key under the v4 separator, refused as an unsupported schema. Every vector on
disk must be named in an index and exercised, which is what caught the five new ones being unindexed.

## 10. Runtime update invalidation

The control plane compares the attested digest against a configured set of accepted implementations. A
different digest is `RUNTIME_IMPLEMENTATION_MISMATCH`; an empty set accepts nothing, exactly as the accepted
subject set does.

Tested with everything else held constant — same key, subject, profile, controls, freshness — and only the
binary differing, plus the anti-vacuity case where accepting that binary authorizes. **The operational rule is
that replacing the runtime requires new evidence, and forgetting fails closed.**

## 11. `nodev` measured limitation

gVisor does not implement `MS_NODEV`. Measured in KAAS-19 across every tmpfs configuration, with a device node
on such a filesystem behaving as a device while the baseline runtime refuses the read.

## 12. `nodev` threat analysis

For the absence to matter, a device node must exist on the source filesystem. Every route:

| route | closed by |
|---|---|
| the bundle format carries one | it has no mode, type, owner or device field — asserted structurally |
| the bootstrap creates one | it has no `mknod` call — asserted by reading its source in a test |
| the consumer creates one | filesystem is frozen; bounding set empty, so no `CAP_MKNOD` |
| the consumer replaces the filesystem | remount refused, no mount capability |

Attacked from a JVM against a real delivered bundle: `java_mknod=false`, `java_source_write=false`,
`java_source_chmod=false`, `java_remount=false`.

## 13. `nodev` compensating controls

The four above, each separately tested, plus the fact that the filesystem is populated and frozen before the
unprivileged consumer exists at all.

## 14. `nodev` acceptance decision

**NODEV NOT ENFORCED; COMPENSATING CONTROLS ACCEPTED FOR EXECUTION.** Not "nodev pass", not "equivalent". The
gate asserts `source_mount_nodev=false` so the fact stays visible in every green run.

**What would invalidate the argument:** a format field for modes or types; a device-creating path in the
bootstrap; any capability in the consumer; a writable source view; or a second source filesystem. Each has a
test, so weakening one turns the gate red rather than quietly widening the acceptance.

## 15. Bootstrap privilege

`CAP_SYS_ADMIN`, `CAP_SETUID`, `CAP_SETGID`, `CAP_SETPCAP`, held by a platform-owned static binary with a fixed
argument vector, for the length of one populate-and-freeze.

## 16. Privilege-drop proof

Order: clear the bounding set, set no-new-privs, drop group, drop user, verify root cannot be regained, exec.
Evidence comes from the far side — the consumer reads its own `CapInh`, `CapPrm`, `CapEff`, `CapBnd`, `CapAmb`
and `NoNewPrivs` out of `/proc`, and a JVM does the same independently.

**No tenant content is interpreted during construction.** The bootstrap reads bytes, checks paths, writes
files and calls `mount` with compile-time constants. Nothing it read reaches a privileged operation.

## 17. Final consumer security context

Mediating runtime, uid/gid 65534, empty capabilities, no-new-privs, read-only root, frozen source filesystem,
the execution's own network policy, and the profile's resource ceilings. Measured from a JVM, which is what a
future engine will be.

## 18. Source filesystem

Read-only and `noexec` enforced and proven; `nosuid` reported on a runtime that performs no setuid transitions
anywhere; `nodev` absent and accepted per §14. One source mount visible, zero non-tmpfs mounts under `/kaas`.

## 19. Writable filesystems

| mount | rw | exec | suid | dev | size | purpose |
|---|---|---|---|---|---|---|
| `/kaas/source` | no | **no** | no | *(unimplemented)* | bundle ceiling + overhead | tenant source |
| `/tmp` | yes | **no** | no | no | 16 MiB | scratch |
| `/dev/shm` | yes | **no** | no | no | 16 MiB | shared memory |
| root filesystem | no | — | — | — | — | image |

**`/tmp` being writable is accepted and its being `noexec` is what makes it acceptable**: hostile code can
write a program and cannot run it — measured as `java_tmp_write=true` with `java_tmp_exec=false`, both halves,
because the write succeeding is what makes the refusal meaningful.

## 20. Process execution

`java_child_spawned=true`. Not a finding: arbitrary code spawning a process is expected under the chosen
model. It is bounded by the PID ceiling and dies with the container.

## 21. Child-process containment

A per-execution container is removed on every path — success, failure, timeout, authority loss and launcher
exception alike — so a child cannot outlive the sandbox that contains it. Cancellation terminates the sandbox
rather than a process inside it, which is the property KAAS-16 established and which this slice inherits
unchanged.

## 22. Network and raw sockets

The platform must be safe when tenant code ignores an engine's HTTP client and opens a socket itself, which
Java interop makes a one-liner. Four destinations from a JVM — DNS, a public address, cloud metadata, and
loopback — all refused. The enforcement is topological: there is no route, so it does not depend on any
engine honouring configuration.

## 23. DENY_ALL

No network at all. Measured from Java, not only from shell.

## 24. ALLOWLIST

Unchanged by this slice: internal-only network, proxy-only routing, policy resolved server-side per request,
redirects re-classified. The active-code reading is that these are topological properties rather than client
configuration, so a hostile JVM does not change the analysis.

## 25. Control-plane isolation

No daemon socket, no worker credential, no capability token in the sandbox. The source capability is redeemed
by the runner before the sandbox exists and never enters it; source arrives as bytes on a stream that is closed
before the consumer runs.

## 26. Capability hygiene

Every set empty, read by the consumer. `RUNTIME_IMPLEMENTATION_MISMATCH` was added as a distinct verification
reason rather than folded into an existing one, because "wrong host" and "wrong program" need different
operator actions.

## 27. Environment and system properties

Nine environment entries, all JVM and locale defaults, asserted by name against a closed set — no `KAAS`, no
`TOKEN`, no `CAPABILITY`, no `SECRET`. The environment is built from empty rather than filtered, so a new
credential in the launcher's environment cannot become a new leak in the sandbox.

## 28. JVM hostile-code evaluation

Summarised in §1 and detailed in the readiness matrix. The JVM-specific findings a shell probe could not have
produced: threads are bounded by the PID ceiling (49 of 200, then `pthread_create EAGAIN`), and a JVM starts
and completes within the production profile of 256 MiB, 64 PIDs and 16 MiB of scratch.

## 29. Karate version evaluation

**2.1.2**, `io.karatelabs:karate-core`, MIT, published 2026-08-14. The older `com.intuit.karate` coordinates
stop at 1.4.1 (2023-10-16), so a pin must use the new groupId. Declared runtime dependencies: `karate-js`,
`json-smart`, `json-path` 3.0.0, `httpclient5` 5.6.3, `netty` 4.2.16, `snakeyaml` 2.6, `fastcsv`, `thymeleaf`
3.1.5, `picocli`, `brotli-dec`, and `logback` 1.6.1. No CVE review was performed for these versions and that
belongs to the slice that pins them.

## 30. Karate capability inventory

In `docs/security/karate-hostile-execution-threat-model.md`, classified per the brief's scheme with no
ambiguous entries. Two are marked UNKNOWN and both are KAAS-21 work: the exact semantics of `read()` on a URL,
and the resolution rules for `classpath:` and relative includes.

## 31. Java interop

Unrestricted. `JavaType(String)` → `Class.forName(name, true, Thread.currentThread().getContextClassLoader())`,
read from the bytecode. No allowlist, no filter, no gate. `javaBridgeEnabled` does not appear in
`karate-core-2.1.2.jar` at all — the control the documentation mentions applies to mocks in a different
context and is absent from this artifact.

## 32. JavaScript

`karate-js`, the project's own engine, which replaced GraalJS in 2.x. Its stated feature is "full support for
reflection-based Java interop". GraalJS restricted host access by default; this does not.

## 33. `read` / `call` / path semantics

The one capability class that containment cannot address, because reading a file inside the sandbox is not an
escape from the sandbox — it is an escape from the *authorized set*, which is a platform property. **KAAS-21
must bound source resolution to `/kaas/source` and prove traversal fails**, rather than trusting the engine's
own normalisation. The bundle is otherwise closed by construction: the snapshot pins exact revisions, all are
present before execution, and the engine holds no credential to fetch another.

## 34. Dynamic loading, reflection, native access

All reachable, all ALLOWED under the chosen model, all contained by the sandbox. Dynamic loading from a URL is
additionally contained by there being no network under `DENY_ALL`.

## 35. The model decision

**FULLY HOSTILE PROGRAMMABLE WORKLOAD**, in `docs/security/karate-execution-model.md`. Chosen on evidence
rather than preference: the restricted model would require a denylist over a reflective interpreter, which
`Class.forName` defeats by construction, or a fork of the engine's class resolution maintained forever.

## 36. Future engine image

Under this model the classpath is the control governing what exists to be reached. The image must carry a JRE,
Karate and its declared dependencies and nothing else — no control-plane client, no Docker client, no cloud
SDK, no database driver, no secret-provider client, no package manager, no compiler. `Java.type` of a platform
class must fail because the class is absent.

## 37. Resource model

A JVM fits the current profile. 256 MiB is tight for Karate and the first engine slice may need more; that is
a resource decision to make deliberately with evidence, not a security relaxation, and the limits were not
changed here.

## 38. Result boundary

The runner must not treat engine stdout as a result protocol. The platform owns `runId`, `attemptId`, epoch,
timestamps, provenance and engine version; the engine contributes narrowly validated execution evidence through
a platform-owned adapter. Test and infrastructure outcomes stay orthogonal and must not be collapsed onto an
engine exit code.

## 39. Output and artifact restrictions

No HTML reports, no artifact ingestion, no report persistence in the first slice. A Karate failure message may
carry tenant source and later secrets; bounded sanitised summaries only.

## 40. Secret-free restriction

The first slice must refuse any run whose snapshot has secret bindings — in the control plane *and* in the
runner. No empty secret, no test secret, no "provider unavailable but continue". Every acceptance in this
report was reasoned without secrets in the sandbox.

## 41. Source exactness

Unchanged from KAAS-19 and still the strongest invariant: the exact bytes the command authorized are the bytes
visible on the frozen filesystem, re-verified in-sandbox against the manifest.

## 42. Execution authority and cancellation

Unchanged from KAAS-16. Authority is continuous, definitive loss stops the sandbox, and nothing is submitted
after it ends.

## 43. Crash and reconciliation

Unchanged from KAAS-19, and simpler than before: no host staging exists, so the only reconciled resource is
the container, which the orphan reconciler already owns.

## 44. Security review

Findings that changed the work:

- **`withEntrypoint(null)` disabled the image entrypoint** — carried in from KAAS-19's review and already
  fixed; noted because the JVM probe's `SANDBOX_CREATE_FAILED` looked identical at first and was not.
- **The JVM probe image reached for `apk add`** when `javac` was missing from a JRE base. A package manager
  running during a security measurement's build is the shape a pinned supply chain exists to prevent. Replaced
  with a two-stage build, both stages digest-pinned from two sources, no download.
- **A bare runtime registration could not be measured** (P1, found by CI). Docker registers its default runtime
  as `runc`, not a path, so the baseline attestation could not be produced at all. Resolved the way the daemon
  resolves it, with the weaker binding stated rather than hidden.
- **The measurement was untestable** (P1, found by mutation) — see §45.

## 45. Mutation evidence

Eight mutations against the runtime identity, the nodev compensating controls and the acceptance plumbing.
**All eight killed**, two of them only after closing a real gap:

| | outcome |
|---|---|
| R01 the runtime digest omitted from the preimage | KILLED — 22 tests, including the fixed vectors |
| R02 the runtime version omitted | KILLED — 22 tests |
| R03 the verifier ignores the runtime digest | KILLED — by both new semantics tests |
| R05 the producer asserts values instead of measuring | **SURVIVED**, then KILLED — see below |
| R15 the source format accepts a mode field | KILLED (compilation refused it) |
| R16 the bootstrap creates a device node | KILLED — the structural test on its source |
| R20 the accepted-implementation set is silently emptied | **SURVIVED** at first scope, KILLED at the right one |
| R24 a v4 schema is accepted for execution | KILLED — 3 tests |

**R05 is the finding worth keeping.** Replacing the entire measurement with three constants — a fixed version
and a digest of zeroes — left every test in the repository green, because nothing could exercise the
measurement without a real daemon. That is precisely the failure the field exists to prevent: confident, signed
evidence about a program nobody looked at. Fixed by splitting "ask the daemon where" from "measure what is
there", which made the measurement testable and is better design regardless; `RuntimeImplementationTest` now
compares the digest against one computed independently and asserts that two different binaries measure
differently.

**R20** survived a scope that did not include the suites which actually authorize. Re-scoped and killed by 30
tests. Recorded because a survivor caused by a bad scope is not a survivor, and telling the two apart is the
harness's job.

## 46. Harness validation

The harness from KAAS-18, unchanged and still holding to its rules: a unique anchor, a verified edit, a forced
clean re-execution, a verdict parsed from JUnit XML rather than grepped from build output, and restoration on
every path including a signal. Its three known outcomes were validated when it was built.

## 47. QE evidence

- `RuntimeImplementationTest` — 5 tests: the digest is of the registered file; two binaries measure
  differently; a missing registration refuses; a directory refuses; a bare name resolves as the daemon would.
- `VerifiedAttestationSemanticsTest` — extended with a replaced-binary refusal and its anti-vacuity
  counterpart, plus the empty-accepted-set refusal.
- `AttestationSigningVectorTest` — five new vectors, each indexed and exercised, including `superseded-v4`.
- `HostileJvmContainmentTests` — 7 tests under real gVisor: profile feasibility, privilege state, the source
  filesystem attacked from Java, generated-code execution, raw networking, platform authority, concurrency.
- Both contract-side implementations and the Python generator agree on the v5 preimage byte for byte.

## 48. CI evidence

The strong-runtime gate names `HostileJvmContainmentTests` explicitly and raises its executed floor. It reads
back the runtime identity from the verifier's own output and **compares the attested digest against the
`sha256sum` of the file the job installed**, so a producer that measured the wrong binary fails the gate. It
then proves the invalidation invariant by verifying the same document against a different accepted
implementation and requiring refusal.

The JVM containment evidence is read back from a file the suite writes, keyed on the properties that matter:
capabilities, no-new-privs, the four source-filesystem refusals including `mknod`, both halves of the
generated-code case, raw egress, and the bounded-concurrency facts.

## 49. Documentation reconciliation

New: ADR-032, `docs/security/execution-readiness-matrix.md`, `docs/security/karate-execution-model.md`,
`docs/security/karate-hostile-execution-threat-model.md`, `docs/security/tenant-execution-readiness.md`.
Earlier ADRs are not rewritten.

## 50. Files changed

New: `RuntimeImplementation`, `RuntimeImplementationTest`, `HostileJvmProbe.java` and its two-stage image,
`HostileJvmContainmentTests`, five documents, five signing vectors.

Modified: both attestation implementations and the Python generator (v5), the schema and every fixture, the
verifier and its property allowlist, `AttestationVerification`, `VerifiedSandboxSecurityAttestation`,
`SandboxSecurityAttestationSource`, `ExecutionAuthorizationService`, the producer and its CLI, the verification
CLI and its Gradle task, `SyntheticProbe`, `SandboxTestSupport`, the runner build script, the CI workflow, and
the test configurations that must now name an accepted implementation.

## 51. Local verification

Full `cleanTest build` on Java 25 / Gradle 9.7.1 with PostgreSQL and RabbitMQ Testcontainers, plus web,
contracts, audit and whitespace gates. **739 tests, 0 failures, 0 skips.**

Also measured locally, because it is meaningful off gVisor: a JVM under the production profile, which is how
the feasibility and `/tmp` findings were first obtained before CI confirmed them under the mediating runtime.

**No Karate dependency exists in any build file**, verified by search.

## 52. GitHub Actions verification

Recorded in §58's addendum with the final run.

## 53. Required-check governance

The new suite sits in a job with no `if:` and no `continue-on-error` and fails rather than skips without the
runtime. **CI present and non-skippable** is what that establishes; whether branch protection requires it is
administration state this report cannot read and does not claim.

## 54. Accepted residual risks

1. `nodev` not enforced by the runtime — §14.
2. The construction phase holds capabilities briefly — §15, §16.
3. The measure-to-use window on the runtime binary — deployment integrity is the control.
4. A bare runtime registration is a weaker binding than an absolute one.
5. Provider metadata endpoints that are globally routable need a deployment-level control.
6. Karate dependency versions have had no CVE review; that belongs to the slice that pins them.

## 55. Remaining blockers

None to *starting* the first execution slice. Three things that slice must build before it is complete:
source-read containment bounded to `/kaas/source`; a determination of `read()`-of-URL behaviour; and a
platform-owned result adapter.

## 56. Exact KAAS-21 scope

One secret-free Karate execution path, and nothing else: the exact authorized FeatureRevision bundle; the
mediated runtime only; source from the prepared frozen filesystem only; no secrets, with refusal in both the
control plane and the runner; existing egress policies only; a fixed pinned Karate version and a fixed platform
adapter; a bounded platform-shaped result; no rich artifacts and no report persistence; and no tenant control
of engine, runtime, image, mounts, JVM flags or system properties.

## 57. Recommended next slice

That one. It is now the smallest well-defined piece of work with everything it depends on either measured or
explicitly listed as its own requirement.

## 58. Final verdict

**READY FOR SECRET-FREE KARATE EXECUTION SLICE**

The runtime that will confine tenant code is now named in the signature, and replacing it invalidates the
evidence. The filesystem tenant code would read from holds against a JVM. Nothing survives the privilege drop.
Raw Java networking reaches nothing. Generated code can be written and cannot be run.

What is accepted is accepted in writing, with tests that fail if the reasoning stops being true — the missing
`nodev` above all, which is recorded as missing in every green run rather than argued into a pass.
