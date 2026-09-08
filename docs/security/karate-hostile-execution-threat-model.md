# Karate hostile execution threat model

What tenant-authored Karate source could attempt once it becomes instructions, and what stops each attempt.
Written before Karate exists in this repository, because the point of adjudicating first is to know what is
being admitted.

Two things are kept apart throughout, and conflating them is the mistake this document exists to prevent:

- **ENGINE CAPABILITY** — what the interpreter lets source do.
- **SANDBOX CONTAINMENT** — what happens when it does.

A capability is not a vulnerability. An uncontained capability is.

## The engine

Karate **2.1.2**, `io.karatelabs:karate-core`, MIT, published 2026-08-14. JavaScript is `karate-js`, the
project's own engine, which replaced GraalJS in Karate 2.x. Declared runtime dependencies: `karate-js`,
`json-smart`, `json-path`, `httpclient5`, `netty-handler`, `netty-codec-http`, `snakeyaml`, `fastcsv`,
`thymeleaf`, `picocli`, `brotli-dec`, and `logback` at runtime scope.

## Capability inventory

Classified per §26 with no "probably blocked".

| capability | engine | classification | what contains it |
|---|---|---|---|
| `Java.type(...)` → any class | **available, unrestricted** — `Class.forName` on the context classloader, no filter | **ALLOWED** | classpath minimality; sandbox |
| construct + invoke by reflection | available | **ALLOWED** | sandbox |
| `Runtime.exec` / `ProcessBuilder` | reachable through interop | **ALLOWED** | PID ceiling; dies with sandbox; `java_child_spawned=true` measured |
| dynamic class loading (`URLClassLoader`, `Class.forName`) | reachable | **ALLOWED** | no network to fetch from under `DENY_ALL`; classpath is fixed |
| define bytecode at runtime | reachable | **ALLOWED** | contained; no host effect |
| native library load (`System.load`) | reachable | **ALLOWED** | gVisor mediates syscalls regardless of who issues them |
| write files | reachable | **ALLOWED, BOUNDED** | scratch tmpfs only: sized, `noexec`, ephemeral |
| execute a written file | reachable | **DENIED BY SANDBOX** | `java_tmp_exec=false` measured |
| write/replace/chmod source | reachable | **DENIED BY SANDBOX** | frozen read-only filesystem; all measured `false` |
| create a device node | reachable | **DENIED BY SANDBOX** | read-only filesystem + empty bounding set; `java_mknod=false` |
| raw sockets, DNS | reachable | **DENIED BY TOPOLOGY** under `DENY_ALL`; proxy-only under `ALLOWLIST` | measured for four destinations |
| read environment / system properties | available | **ALLOWED** | environment is built from empty; asserted to carry no credential |
| threads / async | available | **ALLOWED, BOUNDED ONLY BY MEMORY AND WALL CLOCK** | the PID ceiling does not bound Java threads under the mediating runtime — 200 of 200 attempted start, measured. An accepted residual, not a control |
| `read()` relative to the feature | available | **ALLOWED** | measured: resolves against the feature's own directory; the bundle's normal include mechanism |
| `call read('helper.feature')` | available | **ALLOWED** | measured: works within the bundle |
| `read()` with `../` past the source root | available | **ALLOWED, CONTAINED** | measured: Karate normalises and resolves OUTSIDE `/kaas/source` — `../../../etc/hostname` became `/kaas/etc/hostname`. Karate applies NO root confinement. Only the container filesystem bounds it |
| `read('file:/absolute')` | available | **ALLOWED, CONTAINED** | measured: `read('file:/etc/passwd')` returned the container's file and `contains 'root'` matched. Reads the IMAGE, which is platform-built and secret-free |
| `read('classpath:…')` | available | **ALLOWED, CONTAINED** | measured: `classpath:META-INF/MANIFEST.MF` resolved. The engine classpath is a platform-owned security control — see the isolated engine module |
| `read('http://…')` | **NOT a network operation** | **N/A** | measured: Karate 2.1.2 does not treat a URL scheme as a URL here. It resolved `http://example.com/` as the relative path `…/features/http:/example.com` and failed to open it |
| HTTP via `Given url` / `method get` | available | **DENIED BY TOPOLOGY** under `DENY_ALL` | measured: `java.net.UnknownHostException` — no resolver and no route. Reported as a tenant FAILED, never as an engine error |
| XML / YAML / JSON parsing | available | **ALLOWED** | resource limits; see parser surface |
| HTTP client (`httpclient5`, netty) | available | **ALLOWED** | proxy-only topology; not engine configuration |
| Java `SecurityManager` | not used, and must not be | **N/A** | obsolete; the runtime is the boundary |

## The attack paths worth naming

### Source escaping the authorized bundle

**MEASURED IN KAAS-21, AND THE ANSWER IS NOT THE COMFORTABLE ONE.** Karate 2.1.2 applies no root confinement
to `read()`. A relative path with enough `../` resolves above `/kaas/source`, and `read('file:/absolute')`
opens an absolute path directly — both were demonstrated against a running engine rather than reasoned about.

The earlier expectation, written here before the measurement, was that KAAS-21 would "bound source resolution
to `/kaas/source` and prove traversal outside it fails". That is not what shipped, and the reason is worth
stating plainly rather than quietly dropping: **a wrapper there would not be a boundary.** Tenant source is
arbitrary JVM code — KAAS-20 established that by disassembly — so anything Karate's `read()` can open,
`java.nio.file.Files.readAllBytes` can open beside it. Constraining one API while the other is a `Java.type`
away is a control that looks like enforcement and enforces nothing.

So the platform does not pretend to confine reads. It confines **what there is to read**:

- The engine image is platform-built, digest-pinned, and contains no credential, no key material, and no
  tenant data. Reading it discloses the contents of a published image.
- The sandbox's environment is **exactly three variables** — `LD_LIBRARY_PATH`, `SHLVL`, `PWD` — all created
  after the boundary closes, by the handover shell and the JVM launcher. The bootstrap's `execve` passes an
  empty `envp`, so nothing from the host, the image's `ENV`, or the command reaches the process. Measured,
  and asserted exactly rather than by absence of known-bad names.
- No host path is mounted. There is no other tenant's data on the filesystem to reach.
- The run is **secret-free by refusal**: `ENGINE_REQUIRES_SECRET_FREE_RUN` is returned before the provider
  check, so no secret is present to be read.

What remains genuinely bounded by the platform is the **authorized set that gets EXECUTED**, which is a
different question from what can be read. The adapter runs exactly the manifest the bootstrap wrote from the
platform's own frame — no directory scan, no glob, no default, and a third path-escape refusal at the last
point before a path becomes a file the engine opens. Karate's own default is to scan a directory; if the
adapter did that, the authorized set would be whatever the filesystem happened to contain.

The bundle is closed by construction: the run snapshot pins exact FeatureRevisions, they are all present
before execution starts, and the engine holds no credential with which to fetch another.

### Forged results

A hostile engine printing `workload_outcome=PASSED` is the oldest lesson in this repository. The runner does
not treat engine stdout as a result protocol: the platform owns `runId`, `attemptId`, epoch, timestamps,
provenance and engine version, and the engine contributes exactly one value through a platform-owned adapter.

**MEASURED IN KAAS-21.** Karate's own `print` does not reach stdout at all — it goes to the engine's logger —
but `Java.type('java.lang.System').out.println` does, and a feature doing that emitted a bare
`kaas.karate-result.v1=PASSED` line indistinguishable from the adapter's. That is not preventable and is not
treated as preventable. The collector counts how many times each key was seen, and a stream carrying the
result key twice is **refused**: the run becomes `MALFORMED`, which is an infrastructure failure, not a pass.
Forging removes the tenant's result rather than replacing it.

Three further ways a run can end without a verdict were measured, and all three resolve to `ABSENT` rather
than to a pass: a feature that never returns (killed at the wall clock), a feature calling `System.exit(0)`
mid-suite (**container exit status 0, no assertion run**), and a feature flooding stdout past the collector's
ceiling. Anything deriving an outcome from the exit status reports PASSED for the second of those.

### Resource abuse

A JVM changes the profile: measured floor is a JVM that starts and runs within 256 MiB and 64 PIDs, with
threads bounded by the PID ceiling. Parser amplification (billion laughs, deep nesting, YAML construction)
is contained by memory and wall-clock limits rather than by parser configuration, and the selected
dependencies' defaults should be recorded when the dependency is actually added.

### Output carrying tenant content

A Karate failure message may contain source, request bodies and — later — secrets. The first execution slice
returns bounded, sanitised, platform-shaped results only: no HTML reports, no artifact ingestion, no full
bodies. The existing output sanitiser and ceiling already apply.

## What KAAS-21 measured, and where

Every row above marked "measured" came from a running engine, not from documentation. The evidence lives in
`services/runner/src/test/java/com/kaas/runner/sandbox/KarateExecutionTests.java`, which runs in the mandatory
`karate-execution-gate` CI job, under the mediating runtime that job installs. Under the baseline runtime the
bootstrap cannot close the source filesystem (ADR-031: `bootstrap_failure=FREEZE`), so no engine starts and
there is nothing to measure — every row here describes the mediated boundary. That job additionally refuses to pass unless a run reported
`kaas.engine=karate 2.1.2` — a string the adapter can only produce by resolving `karate-meta.properties`
through Karate's own classloader, so a green gate is inconsistent with an adapter that never started an
engine. The version claim is checked independently of the adapter in
`services/karate-engine/src/test/java/com/kaas/karate/KaasKarateAdapterTest.java`, which reads the jar.

## What changes when secrets arrive

Nothing in this document assumes secrets, and the first execution slice refuses any run whose snapshot has
secret bindings. Once secrets exist, two things in the table above change character: environment/system
property reads become interesting, and failure messages become a disclosure channel. Both are why the first
slice is secret-free and why the result boundary is being designed narrow now rather than widened later.
